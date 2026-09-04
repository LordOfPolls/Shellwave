package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGeneratorTest {

    @Test
    fun `generated private PEM parses back to a key whose public half matches the emitted line`() {
        for (algorithm in GeneratedKeyAlgorithm.entries) {
            val generated = generateKeyPair(algorithm)

            SSHClient().use { client ->
                val loaded = client.loadKeys(generated.privateKeyPem, null, null)
                assertEquals(
                    "algorithm=$algorithm",
                    generated.publicKeyLine,
                    opensshPublicKeyLine(loaded.public),
                )
            }
        }
    }

    @Test
    fun `public key line has the expected algorithm prefix`() {
        val expectedPrefix = mapOf(
            GeneratedKeyAlgorithm.ED25519 to "ssh-ed25519 ",
            GeneratedKeyAlgorithm.RSA to "ssh-rsa ",
            GeneratedKeyAlgorithm.ECDSA_P256 to "ecdsa-sha2-nistp256 ",
        )

        for (algorithm in GeneratedKeyAlgorithm.entries) {
            val generated = generateKeyPair(algorithm)

            assertTrue(
                "algorithm=$algorithm line=${generated.publicKeyLine}",
                generated.publicKeyLine.startsWith(expectedPrefix.getValue(algorithm)),
            )
        }
    }
}
