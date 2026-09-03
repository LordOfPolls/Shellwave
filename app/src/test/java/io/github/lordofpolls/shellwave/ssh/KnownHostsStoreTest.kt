package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts.HostEntry
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts.Marker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey

class KnownHostsStoreTest {

    private fun rsaKey(): PublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public

    @Test
    fun `list surfaces plain, revoked, cert-authority and hashed rows, remove drops only the targeted row`() {
        val file = File.createTempFile("known_hosts", "").apply { deleteOnExit() }
        val hostAKey = rsaKey()
        val hostBKey = rsaKey()
        val revokedKey = rsaKey()
        val caKey = rsaKey()
        val hashedKey = rsaKey()

        val hostALine = HostEntry(null, "hosta", KeyType.RSA, hostAKey).line
        val hostBLine = HostEntry(null, "hostb", KeyType.RSA, hostBKey).line
        val revokedLine = HostEntry(Marker.REVOKED, "hostc", KeyType.RSA, revokedKey).line
        val caLine = HostEntry(Marker.CA_CERT, "hostd", KeyType.RSA, caKey).line
        val hashedLine = HostEntry(null, "|1|c2FsdA==|aGFzaA==", KeyType.RSA, hashedKey).line
        file.writeText("$hostALine\n$hostBLine\n$revokedLine\n$caLine\n$hashedLine\n")

        val rows = listKnownHosts(file)
        assertEquals(5, rows.size)
        assertTrue(rows.any { it.hostDisplay == "hosta" && it.marker == null })
        assertTrue(rows.any { it.hostDisplay == "hostb" && it.marker == null })
        assertTrue(rows.any { it.hostDisplay == "hostc" && it.marker == "@revoked" })
        assertTrue(rows.any { it.hostDisplay == "hostd" && it.marker == "@cert-authority" })
        assertTrue(rows.any { it.hostDisplay == "hashed host" && it.marker == null })
        rows.forEach { assertTrue(it.fingerprint.startsWith("SHA256:")) }

        assertTrue(removeKnownHost(file, hostALine))
        assertFalse(removeKnownHost(file, hostALine))

        val remaining = file.readLines()
        assertEquals(4, remaining.size)
        assertEquals(hostBLine, remaining[0])
        assertEquals(revokedLine, remaining[1])
        assertEquals(caLine, remaining[2])
        assertEquals(hashedLine, remaining[3])
        assertNull(listKnownHosts(file).firstOrNull { it.hostDisplay == "hosta" })
    }
}
