package io.github.lordofpolls.shellwave.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** OpenSSH's shape. [net.schmizz.sshj.common.SecurityUtils.getFingerprint] is MD5. */
fun sha256Fingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/**
 * [sessionLabel] is what a multi-tab UI shows against this decision. Two sessions can prompt at
 * once while only one dialog is on screen, and the raw [sessionId] doesn't tell the user which
 * server they are about to trust a key for.
 */
data class HostVerificationRequest(
    val sessionId: Long,
    val sessionLabel: String,
    val hostname: String,
    val keyType: String,
    val fingerprint: String,
    val isMismatch: Boolean,
    val decide: (accept: Boolean) -> Unit,
)

/**
 * Bridges [TofuKnownHostsVerifier], which runs synchronously on sshj's transport thread inside
 * `SSHClient.connect()`'s KEX handling, to a Compose dialog on the main thread. [requestDecision]
 * blocks that thread until the UI decides or [cancel] fires for the same [attemptToken].
 *
 * [cancel] matters because the coroutine calling `ssh.connect()` waits on sshj's own 30s timeout,
 * which has nothing to do with how long a human takes over a fingerprint. If that fires with a
 * decision still pending, the transport thread stays blocked with no idea the attempt is dead, and
 * honouring the decision afterwards means permanently trusting whatever key raised the mismatch: an
 * ignored dialog outlives its timed-out connection and "connect anyway" still writes `known_hosts`.
 * Every caller must [cancel] its token when the attempt ends.
 *
 * [inFlight] is keyed per attempt because several sessions can be open at once, each checking on
 * its own thread. That uniqueness makes a stale [cancel] a harmless no-op.
 */
@Singleton
class HostVerificationGate @Inject constructor() {
    private val _pending = MutableStateFlow<Map<Any, HostVerificationRequest>>(emptyMap())
    val pending: StateFlow<Map<Any, HostVerificationRequest>> = _pending

    private val inFlight = ConcurrentHashMap<Any, CompletableDeferred<Boolean>>()

    /** Unique to the attempt asking; typically the `TofuKnownHostsVerifier` instance itself. */
    fun requestDecision(
        attemptToken: Any,
        sessionId: Long,
        sessionLabel: String,
        hostname: String,
        keyType: String,
        fingerprint: String,
        isMismatch: Boolean
    ): Boolean =
        runBlocking {
            val deferred = CompletableDeferred<Boolean>()
            inFlight[attemptToken] = deferred
            _pending.update {
                it + (attemptToken to HostVerificationRequest(
                    sessionId,
                    sessionLabel,
                    hostname,
                    keyType,
                    fingerprint,
                    isMismatch
                ) { accepted ->
                    if (inFlight.remove(attemptToken) != null) {
                        _pending.update { m -> m - attemptToken }
                        deferred.complete(accepted)
                    }
                })
            }
            deferred.await()
        }

    /**
     * Dismisses the dialog and resolves the possibly still-blocked [requestDecision] with `false`: a
     * dead attempt rejects the host key rather than leaving anything hanging. Safe to call
     * unconditionally once an attempt ends.
     */
    fun cancel(attemptToken: Any) {
        val deferred = inFlight.remove(attemptToken)
        if (deferred != null) {
            _pending.update { it - attemptToken }
            deferred.complete(false)
        }
    }
}

/**
 * Unknown host raises a TOFU dialog; a matching host with a different key raises the hard-block
 * dialog through the same gate. Acceptance either way appends the entry, so the next connection
 * needs no prompt.
 *
 * The sole host key verifier in the app. There is no `PromiscuousVerifier` anywhere.
 */
class TofuKnownHostsVerifier(
    file: File,
    private val gate: HostVerificationGate,
    private val sessionId: Long,
    private val sessionLabel: String
) : OpenSSHKnownHosts(file) {

    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        val accepted = gate.requestDecision(
            this,
            sessionId,
            sessionLabel,
            hostname,
            KeyType.fromKey(key).toString(),
            sha256Fingerprint(key),
            isMismatch = false
        )
        if (accepted) persist(hostname, key)
        return accepted
    }

    override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
        val accepted = gate.requestDecision(
            this,
            sessionId,
            sessionLabel,
            hostname,
            KeyType.fromKey(key).toString(),
            sha256Fingerprint(key),
            isMismatch = true
        )
        if (accepted) persist(hostname, key)
        return accepted
    }

    private fun persist(hostname: String, key: PublicKey) {
        runCatching { write(HostEntry(null, hostname, KeyType.fromKey(key), key)) }
    }

    /** Call once the attempt this was built for ends, for any reason. */
    fun cancel() {
        gate.cancel(this)
    }
}
