package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * Pure logic, no device dependency. The key is generated fresh - never a checked-in fixture.
 *
 * [parsePublicKeyLine] is what stands between a stored `publicKeyText` and the fingerprint the Keys
 * screen shows, and it swallows every failure into `null`, which renders as a key with no
 * fingerprint rather than an error.
 *
 * Assertions compare the re-encoded line and the fingerprint, not the `PublicKey` objects: a key
 * rebuilt through sshj's buffer is not `equals` to the JDK-generated original even when it carries
 * the same material.
 */
class KeyImportPublicKeyLineTest {

    private fun publicKey() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        .generateKeyPair()
        .public

    @Test
    fun roundTripsAnOpensshLine() {
        val original = publicKey()
        val line = opensshPublicKeyLine(original)

        val parsed = parsePublicKeyLine(line)

        assertNotNull(parsed)
        assertEquals(sha256Fingerprint(original), sha256Fingerprint(parsed!!))
        assertEquals(line, opensshPublicKeyLine(parsed))
    }

    @Test
    fun toleratesSurroundingWhitespaceAndAMissingComment() {
        val line = opensshPublicKeyLine(publicKey()).split(" ").take(2).joinToString(" ")

        val parsed = parsePublicKeyLine("  $line\n")

        assertNotNull(parsed)
        assertEquals(line, opensshPublicKeyLine(parsed!!).split(" ").take(2).joinToString(" "))
    }

    @Test
    fun returnsNullRatherThanThrowingOnJunk() {
        assertNull(parsePublicKeyLine(""))
        assertNull(parsePublicKeyLine("ssh-ed25519"))
        assertNull(parsePublicKeyLine("ssh-ed25519 not-base64!!"))
        assertNull(parsePublicKeyLine("ssh-ed25519 AAAA comment"))
    }
}
