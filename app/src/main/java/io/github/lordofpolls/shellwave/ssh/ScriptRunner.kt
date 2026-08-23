package io.github.lordofpolls.shellwave.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A script emitting 100 MB must not put 100 MB into SQLite. */
internal const val CAPTURE_STREAM_CAP_BYTES = 256 * 1024

internal const val CAPTURE_TIMEOUT_MS = 120_000L

/**
 * [stdout]/[stderr] arrive already capped to [CAPTURE_STREAM_CAP_BYTES]; the truncation flags let
 * the caller append its own marker, which is a storage concern rather than this class's.
 *
 * [error] replaces [exitStatus] when the run never reached the command at all - a connect, auth or
 * host-key failure, or the timeout. The two are never both set.
 */
data class CaptureResult(
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val exitStatus: Int?,
    val error: String?,
)

/**
 * Headless `exec()` runner for capture-mode scripts and [KeyEnrolment]'s `ssh-copy-id` equivalent.
 * An injectable `@Singleton` holding no `Activity` or composition, since a capture run can come
 * from a widget tap or a tile as easily as from a screen.
 *
 * [runCapture] connects through the same path an interactive session uses and checks the host key
 * against the same `known_hosts`, so a host trusted once is trusted the same way whichever route
 * reached it. But `HostVerificationGate.requestDecision` and [KeyboardInteractiveGate]'s prompt
 * block until a foreground UI answers, and a background run has nobody to answer them.
 *
 * So [runCaptureBackground] refuses instead of waiting: a keyboard-interactive credential before
 * connecting at all, and a new or changed host key through BackgroundKnownHostsVerifier, which
 * reads the same file - an already-trusted host connects exactly as before - and returns `false`
 * instead of blocking on a dialog. A background trigger must not accept a host key on the user's
 * behalf; a stale decision permanently trusting an attacker's key has already been a bug here once.
 * [CaptureResult.error] carries a message `ScriptTriggerService` can show, distinguishing an
 * unknown host from a changed key.
 */
@Singleton
class ScriptRunner
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val hostVerificationGate: HostVerificationGate,
    private val keyboardInteractiveGate: KeyboardInteractiveGate,
) {
    /**
     * Runs [command] through `Session.exec` - no PTY, no shell, no emulator - and disconnects
     * afterwards either way. Capture mode is always a one-shot connection.
     *
     * [command] is sent verbatim, so it must arrive fully substituted and quoted (see
     * substituteParams). [runLabel] is display text for the host-key and keyboard-interactive dialogs,
     * so someone asked to trust a key mid-run knows what asked.
     *
     * This is the foreground call: it raises those prompts and blocks until they are answered.
     * [runCaptureBackground] is the other case. Every hop in [hops] uses the same verifier as the
     * target.
     */
    suspend fun runCapture(
        runLabel: String,
        hostname: String,
        port: Int,
        username: String,
        authMethod: AuthMethod,
        command: String,
        hops: List<ProxyHop> = emptyList(),
    ): CaptureResult {
        val knownHosts = TofuKnownHostsVerifier(
            File(context.filesDir, KNOWN_HOSTS_FILE_NAME),
            hostVerificationGate,
            0L,
            runLabel
        )
        (authMethod as? AuthMethod.KeyboardInteractive)?.let {
            keyboardInteractiveGate.label(
                it.provider,
                runLabel
            )
        }
        return try {
            connectExecCapture(hostname, port, username, authMethod, command, knownHosts, hops)
        } finally {
            // Every caller must do this once the attempt ends, whether or not a prompt was ever
            // raised; see HostVerificationGate/KeyboardInteractiveGate's class docs.
            knownHosts.cancel()
            (authMethod as? AuthMethod.KeyboardInteractive)?.let { keyboardInteractiveGate.cancel(it.provider) }
        }
    }

    /**
     * Never raises a prompt and never blocks waiting for one: a [AuthMethod.KeyboardInteractive]
     * credential is refused outright before connecting, and an unknown or changed host key is refused
     * synchronously by [BackgroundKnownHostsVerifier]. Callers with no foreground UI call this, never
     * [runCapture].
     *
     * The keyboard-interactive refusal applies to every hop, jump hosts included, and is checked before
     * anything is connected - a bastion needing a prompt is exactly as unanswerable as the target
     * needing one, and finding out on hop 2 would mean hanging mid-chain.
     */
    suspend fun runCaptureBackground(
        runLabel: String,
        hostname: String,
        port: Int,
        username: String,
        authMethod: AuthMethod,
        command: String,
        hops: List<ProxyHop> = emptyList(),
    ): CaptureResult {
        if (authMethod is AuthMethod.KeyboardInteractive || hops.any { it.authMethod is AuthMethod.KeyboardInteractive }) {
            return CaptureResult(
                "",
                "",
                false,
                false,
                null,
                error = "\"$runLabel\" needs a prompt (keyboard-interactive/2FA) only the app can answer - open Shellwave to run it.",
            )
        }
        val knownHosts = BackgroundKnownHostsVerifier(File(context.filesDir, KNOWN_HOSTS_FILE_NAME))
        return connectExecCapture(hostname, port, username, authMethod, command, knownHosts, hops)
    }

    private suspend fun connectExecCapture(
        hostname: String,
        port: Int,
        username: String,
        authMethod: AuthMethod,
        command: String,
        hostKeyVerifier: HostKeyVerifier,
        hops: List<ProxyHop> = emptyList(),
    ): CaptureResult =
        withContext(Dispatchers.IO) {
            val ssh = SSHClient()
            var chainResources: ProxyChainResources = ProxyChainResources(emptyList(), emptyList())
            try {
                withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                    // hops is empty for the common no-jump case, where this is a plain connect.
                    chainResources = connectChainAndAuthenticate(
                        ssh,
                        hops,
                        hostname,
                        port,
                        username,
                        authMethod,
                        hostKeyVerifier
                    )
                    execAndCollect(ssh, command)
                } ?: CaptureResult(
                    "",
                    "",
                    false,
                    false,
                    null,
                    error = "Timed out after ${CAPTURE_TIMEOUT_MS / 1000}s"
                )
            } catch (e: Exception) {
                // BackgroundKnownHostsVerifier's refusal reads far better than sshj's generic
                // "Could not verify ..." TransportException, and describeConnectFailure does the
                // same job for sshj's misleading auth wording.
                val refusalReason =
                    (hostKeyVerifier as? BackgroundKnownHostsVerifier)?.refusalReason
                CaptureResult(
                    "",
                    "",
                    false,
                    false,
                    null,
                    error = refusalReason ?: describeConnectFailure(e, authMethod)
                )
            } finally {
                runCatching { ssh.disconnect() }
                // After ssh itself, per ProxyChainResources.disconnect's doc; no-op with no hops.
                runCatching { chainResources.disconnect() }
            }
        }
}

/**
 * Reads the same `known_hosts` file TofuKnownHostsVerifier does, so an already-trusted host
 * connects identically either way, but never shows a dialog and never blocks: an unknown or changed
 * key is refused synchronously by returning `false`. [refusalReason] records which case happened,
 * so the caller can report something specific instead of a bare exception.
 */
internal class BackgroundKnownHostsVerifier(file: File) : OpenSSHKnownHosts(file) {
    var refusalReason: String? = null
        private set

    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        refusalReason =
            "No trusted host key for $hostname yet - open Shellwave and connect once to trust it, then re-run this."
        return false
    }

    override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
        refusalReason =
            "The host key for $hostname has changed - a background run won't accept that automatically. Open Shellwave to review it."
        return false
    }
}

/**
 * One `exec` channel on an already-connected, already-authenticated [ssh]: run [command], read both
 * streams to EOF (capped), collect the exit status, close the channel.
 *
 * Shared by the two callers that have nothing else in common - [ScriptRunner.connectExecCapture],
 * which dials a one-shot connection of its own, and [SshConnection.execCapture], which borrows a
 * live session's connection. Extracted rather than duplicated because the details that are easy to
 * get subtly wrong are all in here: both streams must be drained concurrently (a command that fills
 * the stderr window while this reads stdout deadlocks otherwise), the exit status is only readable
 * after `join`, and the channel must be closed on every path.
 *
 * No timeout of its own - the caller owns that, because the two have different ones to apply. Knows
 * nothing about connecting, authenticating or host keys: everything above that line differs between
 * the callers, and this is exactly the part that does not.
 */
internal suspend fun execAndCollect(ssh: SSHClient, command: String): CaptureResult =
    coroutineScope {
        val session = ssh.startSession()
        try {
            val cmd = session.exec(command)
            val stdoutDeferred = async { readCapped(cmd.inputStream, CAPTURE_STREAM_CAP_BYTES) }
            val stderrDeferred = async { readCapped(cmd.errorStream, CAPTURE_STREAM_CAP_BYTES) }
            val (stdout, stdoutTruncated) = stdoutDeferred.await()
            val (stderr, stderrTruncated) = stderrDeferred.await()
            runCatching { cmd.join(10, TimeUnit.SECONDS) }
            CaptureResult(
                stdout,
                stderr,
                stdoutTruncated,
                stderrTruncated,
                cmd.exitStatus,
                error = null
            )
        } finally {
            runCatching { session.close() }
        }
    }

/**
 * Reads [stream] to EOF, keeping at most [capBytes] of it. Once the cap is hit, further bytes are
 * still read and discarded instead of stopping outright: closing the stream early would leave the
 * remote command's writes blocked against a full SSH channel window instead of letting it finish
 * and report a real exit status. Memory stays bounded to `O(capBytes)` however much the remote
 * writes.
 */
private fun readCapped(stream: InputStream, capBytes: Int): Pair<String, Boolean> {
    val buffer = ByteArrayOutputStream(minOf(capBytes, 8192))
    val chunk = ByteArray(8192)
    var truncated = false
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        val remaining = capBytes - buffer.size()
        if (remaining <= 0) {
            truncated = true
        } else if (read > remaining) {
            buffer.write(chunk, 0, remaining)
            truncated = true
        } else {
            buffer.write(chunk, 0, read)
        }
    }
    return buffer.toString(Charsets.UTF_8.name()) to truncated
}
