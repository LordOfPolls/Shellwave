package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts.HostEntry
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts.Marker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey

/**
 * Accepting a changed host key must drop the superseded entry, not just append the new one: sshj's
 * `verify` accepts if ANY entry for the host matches.
 */
class HostKeyRevokeTest {

    private class TestVerifier(file: File, gate: HostVerificationGate) :
        TofuKnownHostsVerifier(file, gate, 1L, "test") {
        fun changed(hostname: String, key: PublicKey) = hostKeyChangedAction(hostname, key)

        fun unverifiable(hostname: String, key: PublicKey) = hostKeyUnverifiableAction(hostname, key)
    }

    private fun rsaKey(): PublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public

    private fun ed25519Key(): PublicKey =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public

    /** `hostKeyChangedAction` blocks on the gate, as it does on sshj's transport thread. */
    private fun acceptOnBackgroundThread(
        gate: HostVerificationGate,
        accept: Boolean = true,
        action: () -> Unit,
    ) {
        val thread = Thread(action)
        thread.start()
        waitForPending(gate)
        gate.pending.value.values.first().decide(accept)
        thread.join(5_000)
    }

    @Test
    fun `accepting a changed key drops the superseded entry but leaves other hosts alone`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val oldKeyA = ed25519Key()
        val keyB = rsaKey()
        file.writeText(
            HostEntry(null, "hosta", KeyType.ED25519, oldKeyA).line + "\n" +
                HostEntry(null, "hostb", KeyType.RSA, keyB).line + "\n"
        )

        val newKeyA = ed25519Key()
        val gate = HostVerificationGate()
        acceptOnBackgroundThread(gate) { TestVerifier(file, gate).changed("hosta", newKeyA) }

        val lines = file.readLines()
        val oldLineA = HostEntry(null, "hosta", KeyType.ED25519, oldKeyA).line
        val newLineA = HostEntry(null, "hosta", KeyType.ED25519, newKeyA).line
        val lineB = HostEntry(null, "hostb", KeyType.RSA, keyB).line
        assertFalse("superseded hosta entry must be gone", lines.contains(oldLineA))
        assertTrue("newly accepted hosta entry must be present", lines.contains(newLineA))
        assertTrue("hostb's entry must survive untouched", lines.contains(lineB))
    }

    @Test
    fun `revoking a changed ed25519 key leaves the same host's rsa entry untouched`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val oldEd25519 = ed25519Key()
        val rsaForSameHost = rsaKey()
        file.writeText(
            HostEntry(null, "hosta", KeyType.ED25519, oldEd25519).line + "\n" +
                HostEntry(null, "hosta", KeyType.RSA, rsaForSameHost).line + "\n"
        )

        val newEd25519 = ed25519Key()
        val gate = HostVerificationGate()
        acceptOnBackgroundThread(gate) { TestVerifier(file, gate).changed("hosta", newEd25519) }

        val lines = file.readLines()
        val rsaLine = HostEntry(null, "hosta", KeyType.RSA, rsaForSameHost).line
        assertTrue("other key type for the same host must survive", lines.contains(rsaLine))
    }

    /**
     * Verifier A and B are both built at the start, each snapshotting the file in its constructor -
     * exactly what happens when two sessions connect around the same time. B revoking a different
     * host must not resurrect a stale in-memory copy of the file that drops A's concurrent append.
     */
    @Test
    fun `a revoke on one verifier does not clobber an append made through another`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val hostBOldKey = ed25519Key()
        file.writeText(HostEntry(null, "hostb", KeyType.ED25519, hostBOldKey).line + "\n")

        val gateA = HostVerificationGate()
        val verifierA = TestVerifier(file, gateA)
        val gateB = HostVerificationGate()
        val verifierB = TestVerifier(file, gateB)

        val hostAKey = ed25519Key()
        acceptOnBackgroundThread(gateA) { verifierA.unverifiable("hosta", hostAKey) }

        val hostBNewKey = ed25519Key()
        acceptOnBackgroundThread(gateB) { verifierB.changed("hostb", hostBNewKey) }

        val lines = file.readLines()
        val hostALine = HostEntry(null, "hosta", KeyType.ED25519, hostAKey).line
        assertTrue("append made through verifier A must survive verifier B's revoke", lines.contains(hostALine))
    }

    @Test
    fun `revoking a changed key keeps a marked @revoked entry for the same host and key type`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val revokedKey = ed25519Key()
        val plainKey = ed25519Key()
        val revokedLine = HostEntry(Marker.REVOKED, "hosta", KeyType.ED25519, revokedKey).line
        val plainLine = HostEntry(null, "hosta", KeyType.ED25519, plainKey).line
        file.writeText(revokedLine + "\n" + plainLine + "\n")

        val newKey = ed25519Key()
        val gate = HostVerificationGate()
        acceptOnBackgroundThread(gate) { TestVerifier(file, gate).changed("hosta", newKey) }

        val lines = file.readLines()
        assertTrue("marked @revoked entry must survive", lines.contains(revokedLine))
        assertFalse("plain superseded entry must be gone", lines.contains(plainLine))
    }

    /**
     * A correctly-verifying key resolves without asking the gate at all, so it can't be driven from
     * [acceptOnBackgroundThread]. Bounded so a regression that falls through to an unexpected prompt
     * fails the test instead of hanging the run - `hostKeyChangedAction` would otherwise block this
     * thread on the gate forever, since nothing is here to answer it.
     */
    private fun verifyWithTimeout(verifier: TestVerifier, hostname: String, key: PublicKey): Boolean {
        var result: Boolean? = null
        val thread = Thread { result = verifier.verify(hostname, 22, key) }.apply { isDaemon = true }
        thread.start()
        thread.join(5_000)
        check(!thread.isAlive) { "verify() blocked on an unexpected host-key prompt" }
        return result!!
    }

    @Test
    fun `after an accepted key change the same verifier stops accepting the old key and accepts the new one`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val oldKey = ed25519Key()
        file.writeText(HostEntry(null, "hosta", KeyType.ED25519, oldKey).line + "\n")

        val newKey = ed25519Key()
        val gate = HostVerificationGate()
        val verifier = TestVerifier(file, gate)
        acceptOnBackgroundThread(gate) { verifier.changed("hosta", newKey) }

        assertTrue(
            "the just-accepted key must verify with no further prompt",
            verifyWithTimeout(verifier, "hosta", newKey),
        )

        // Pre-fix the stale entry stayed in `entries()` and the old key kept verifying silently.
        var oldKeyAccepted = true
        acceptOnBackgroundThread(gate, accept = false) {
            oldKeyAccepted = verifier.verify("hosta", 22, oldKey)
        }
        assertFalse("the old key must no longer verify silently", oldKeyAccepted)
    }

    /** Blocks the caller until [gate] has a pending request, without deciding it. */
    private fun waitForPending(gate: HostVerificationGate) {
        val deadline = System.currentTimeMillis() + 5_000
        while (gate.pending.value.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for a pending request" }
            Thread.sleep(1)
        }
    }

    @Test
    fun `an unknown host blocks on the gate and is not written until a decision`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val key = ed25519Key()
        val gate = HostVerificationGate()
        val verifier = TestVerifier(file, gate)

        var result: Boolean? = null
        val thread = Thread { result = verifier.unverifiable("hosta", key) }
        thread.start()
        waitForPending(gate)

        assertTrue(
            "must not write known_hosts before a decision is made",
            file.readText().isBlank(),
        )

        gate.pending.value.values.first().decide(true)
        thread.join(5_000)

        assertTrue("an accepted unknown host must return true", result == true)
        assertTrue(
            "must write known_hosts once accepted",
            file.readText().contains(HostEntry(null, "hosta", KeyType.ED25519, key).line),
        )
    }

    @Test
    fun `cancel on the gate resolves a pending decision to reject`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val key = ed25519Key()
        val gate = HostVerificationGate()
        val verifier = TestVerifier(file, gate)

        var result: Boolean? = null
        val thread = Thread { result = verifier.unverifiable("hosta", key) }
        thread.start()
        waitForPending(gate)

        gate.cancel(verifier)
        thread.join(5_000)

        assertEquals("a cancelled attempt must reject the host key", false, result)
        assertTrue("a rejected key must never be written", file.readText().isBlank())
        assertTrue("cancel must clear the pending request", gate.pending.value.isEmpty())
    }

    /**
     * Stands in for sshj's own connect timeout firing with the dialog still up (see
     * HostVerificationGate's class doc): the pending decision is cancelled, not answered, and the
     * changed key must not be accepted on the strength of that alone.
     */
    @Test
    fun `a changed key is never accepted without an explicit decision`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val oldKey = ed25519Key()
        file.writeText(HostEntry(null, "hosta", KeyType.ED25519, oldKey).line + "\n")
        val newKey = ed25519Key()
        val gate = HostVerificationGate()
        val verifier = TestVerifier(file, gate)

        var result: Boolean? = null
        val thread = Thread { result = verifier.changed("hosta", newKey) }
        thread.start()
        waitForPending(gate)

        gate.cancel(verifier)
        thread.join(5_000)

        assertEquals("a timed-out/cancelled key change must reject, not accept", false, result)
        val lines = file.readLines()
        assertTrue(
            "the superseded key must survive - nothing was ever accepted",
            lines.contains(HostEntry(null, "hosta", KeyType.ED25519, oldKey).line),
        )
        assertFalse(
            "the new key must never be written",
            lines.contains(HostEntry(null, "hosta", KeyType.ED25519, newKey).line),
        )
    }
}
