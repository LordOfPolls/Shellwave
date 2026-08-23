package io.github.lordofpolls.shellwave.feature.scripts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.crypto.isBiometricCancellation
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.ScriptRunDao
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptRunEntity
import io.github.lordofpolls.shellwave.core.util.substituteParams
import io.github.lordofpolls.shellwave.ssh.AuthMethod
import io.github.lordofpolls.shellwave.ssh.CaptureResult
import io.github.lordofpolls.shellwave.ssh.ConnectionSpec
import io.github.lordofpolls.shellwave.ssh.ProxyHop
import io.github.lordofpolls.shellwave.ssh.ScriptRunner
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ssh.resolveProxyHops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class CaptureRunUiState(
    val scriptName: String,
    val running: Boolean,
    val run: ScriptRunEntity? = null,
    val error: String? = null
)

/**
 * Drives a script run through whichever of the three modes it declares. One instance is shared by
 * every screen that can trigger a run - the same no-`ViewModel` state-holder pattern as
 * TerminalSelectionState.
 *
 * [request] captures which SshConnection a run should target, and optionally which host to run
 * against, overriding [ScriptEntity.targetHostId]. A connection means two things depending on mode,
 * though both arrive from the same "Run script here" menu: [ScriptMode.SEND_TO_CURRENT] types into
 * that session's shell, while `ScriptMode.CAPTURE` opens its own `exec` channel on it.
 *
 * A null [ScriptEntity.targetHostId] is one more thing to collect before executing, so it joins the
 * collect-then-execute machine already here for `{{param}}` prompting rather than getting a
 * parallel one: [pendingHostChoice] -> [chooseHost] -> [pending] -> [confirmRun] -> `execute`.
 */
class ScriptRunController
internal constructor(
    private val scope: CoroutineScope,
    private val hostDao: HostDao,
    private val credentialVault: CredentialVault,
    private val scriptRunner: ScriptRunner,
    private val scriptRunDao: ScriptRunDao,
    private val sessionManager: SessionManager,
    private val activity: FragmentActivity?,
    private val onSessionOpened: (Long) -> Unit,
) {
    var pending by mutableStateOf<ScriptEntity?>(null)
        private set
    private var pendingConnection: SshConnection? = null

    /**
     * A script saved with "ask each run", launched from somewhere that does not imply a host.
     * ScriptRunDialogs renders the picker; [chooseHost] answers it.
     */
    var pendingHostChoice by mutableStateOf<ScriptEntity?>(null)
        private set

    /** The caller's override, the script's own target, or the picker's answer. */
    private var pendingHostId: Long? = null

    var captureUiState by mutableStateOf<CaptureRunUiState?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * [activeConnection] is the session to run on: required for [ScriptMode.SEND_TO_CURRENT], and for
     * `ScriptMode.CAPTURE` it means "run it on this open connection" (see [runCaptureMode]). [hostId]
     * overrides [ScriptEntity.targetHostId] for callers that already know the host.
     */
    fun request(
        script: ScriptEntity,
        activeConnection: SshConnection? = null,
        hostId: Long? = null
    ) {
        pendingConnection = activeConnection
        pendingHostId = hostId ?: script.targetHostId
        val mode = runCatching { ScriptMode.valueOf(script.mode) }.getOrNull()
        // SEND_TO_CURRENT targets a session and never a host. Nor does a CAPTURE run that arrived
        // with a live connection: "run it here" already named the machine.
        val runsOnActiveConnection =
            mode == ScriptMode.SEND_TO_CURRENT || (mode == ScriptMode.CAPTURE && activeConnection != null)
        if (pendingHostId == null && !runsOnActiveConnection) {
            pendingHostChoice = script
        } else {
            pending = script
        }
    }

    fun chooseHost(script: ScriptEntity, hostId: Long) {
        pendingHostId = hostId
        pendingHostChoice = null
        pending = script
    }

    fun dismiss() {
        pending = null
        pendingHostChoice = null
        pendingConnection = null
        pendingHostId = null
    }

    fun clearError() {
        error = null
    }

    /** So MainActivity's direct connect paths share this error dialog instead of owning one. */
    fun reportError(message: String) {
        error = message
    }

    fun dismissCapture() {
        captureUiState = null
    }

    fun confirmRun(script: ScriptEntity, values: Map<String, String>) {
        val connection = pendingConnection
        val hostId = pendingHostId
        pending = null
        pendingConnection = null
        pendingHostId = null
        scope.launch { execute(script, values, connection, hostId) }
    }

    private suspend fun execute(
        script: ScriptEntity,
        values: Map<String, String>,
        activeConnection: SshConnection?,
        hostId: Long?
    ) {
        when (runCatching { ScriptMode.valueOf(script.mode) }.getOrNull()) {
            ScriptMode.CAPTURE -> runCaptureMode(script, values, hostId, activeConnection)
            ScriptMode.ATTACH -> runAttachMode(script, values, hostId)
            ScriptMode.SEND_TO_CURRENT -> runSendToCurrent(script, values, activeConnection)
            null -> error = "Unknown script mode \"${script.mode}\""
        }
    }

    /**
     * A non-null [activeConnection] is the "run a script here" path: a capture run on an
     * already-authenticated connection via [SshConnection.execCapture] instead of dialling a second
     * one. Everything after the run is shared with the other path - same redaction, same
     * ScriptRunEntity written to history, same result sheet.
     */
    private suspend fun runCaptureMode(
        script: ScriptEntity,
        values: Map<String, String>,
        hostId: Long?,
        activeConnection: SshConnection?
    ) {
        val command = substituteParams(script.snippet, values)
        val startedAt: Long
        val result: CaptureResult

        if (activeConnection != null) {
            captureUiState = CaptureRunUiState(script.name, running = true)
            startedAt = System.currentTimeMillis()
            result = activeConnection.execCapture(command)
        } else {
            if (hostId == null) {
                // Unreachable through request(), which sends a hostless script to the picker first.
                // Kept because the id is nullable all the way down and a silent no-op would be
                // worse.
                error = "\"${script.name}\" has no host to run against"
                return
            }
            val host = hostDao.getById(hostId)
            if (host == null) {
                error = "The host this script targets no longer exists"
                return
            }
            captureUiState = CaptureRunUiState(script.name, running = true)
            val authMethod: AuthMethod
            val hops: List<ProxyHop>
            try {
                authMethod = credentialVault.resolve(host.credentialId, activity)
                hops = resolveProxyHops(host, hostDao, credentialVault, activity)
            } catch (e: Exception) {
                captureUiState = null
                if (!e.isBiometricCancellation()) error =
                    e.message ?: "Could not unlock this host's saved credential"
                return
            }
            // After the unlock: a biometric prompt sits there as long as the user takes to answer
            // it, and that wait is not part of how long the script ran.
            startedAt = System.currentTimeMillis()
            result = scriptRunner.runCapture(
                "Script: ${script.name}",
                host.hostname,
                host.port,
                host.username,
                authMethod,
                command,
                hops
            )
        }
        val finishedAt = System.currentTimeMillis()

        // Secret param values must never be persisted. The runner never writes them itself, but the
        // remote command could have echoed one back on stdout/stderr.
        val secretValues = decodeParams(script.paramsJson).filter { it.type == ParamType.SECRET }
            .mapNotNull { values[it.name] }.filter { it.isNotEmpty() }
        val stdout = redact(mark(result.stdout, result.stdoutTruncated), secretValues)
        val stderr = redact(mark(result.stderr, result.stderrTruncated), secretValues)

        val entity = ScriptRunEntity(
            scriptId = script.id,
            startedAt = startedAt,
            finishedAt = finishedAt,
            exitStatus = result.exitStatus,
            stdout = stdout,
            stderr = stderr
        )
        val id = scriptRunDao.insert(entity)
        captureUiState = CaptureRunUiState(
            script.name,
            running = false,
            run = entity.copy(id = id),
            error = result.error
        )
    }

    private suspend fun runAttachMode(
        script: ScriptEntity,
        values: Map<String, String>,
        hostId: Long?
    ) {
        if (hostId == null) {
            error = "\"${script.name}\" has no host to run against"
            return
        }
        val host = hostDao.getById(hostId)
        if (host == null) {
            error = "The host this script targets no longer exists"
            return
        }
        val authMethod: AuthMethod
        val hops: List<ProxyHop>
        try {
            authMethod = credentialVault.resolve(host.credentialId, activity)
            hops = resolveProxyHops(host, hostDao, credentialVault, activity)
        } catch (e: Exception) {
            if (!e.isBiometricCancellation()) error =
                e.message ?: "Could not unlock this host's saved credential"
            return
        }
        val spec = ConnectionSpec(
            host.hostname,
            host.port,
            host.username,
            authMethod,
            host.id,
            host.resilientSession,
            hops
        )
        val sessionId = sessionManager.openSession(spec)
        onSessionOpened(sessionId)
        val command = substituteParams(script.snippet, values)
        val summary =
            sessionManager.summaries
                .map { list -> list.firstOrNull { it.id == sessionId } }
                .filterNotNull()
                .first { it.status != SessionStatus.CONNECTING }
        if (summary.status == SessionStatus.CONNECTED) {
            summary.connection.write("$command\n")
            if (script.disconnectAfter) summary.connection.write("exit\n")
        } else {
            error = summary.error ?: "Connection failed"
        }
    }

    private fun runSendToCurrent(
        script: ScriptEntity,
        values: Map<String, String>,
        activeConnection: SshConnection?
    ) {
        if (activeConnection == null) {
            error = "Open a session first to use \"${script.name}\""
            return
        }
        val command = substituteParams(script.snippet, values)
        activeConnection.write("$command\n")
    }
}


@Composable
fun rememberScriptRunController(
    hostDao: HostDao,
    credentialVault: CredentialVault,
    scriptRunner: ScriptRunner,
    scriptRunDao: ScriptRunDao,
    sessionManager: SessionManager,
    activity: FragmentActivity?,
    onSessionOpened: (Long) -> Unit,
): ScriptRunController {
    val scope = rememberCoroutineScope()
    return remember {
        ScriptRunController(
            scope,
            hostDao,
            credentialVault,
            scriptRunner,
            scriptRunDao,
            sessionManager,
            activity,
            onSessionOpened
        )
    }
}
