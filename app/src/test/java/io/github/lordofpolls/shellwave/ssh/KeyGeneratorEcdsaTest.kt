package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Pure logic, no device dependency. Generated on the fly - never a checked-in fixture.
 */
class KeyGeneratorEcdsaTest {

    @Test
    fun `an ECDSA P-256 key round-trips through sshj and reports the nistp256 key type`() {
        val key = generateKeyPair(GeneratedKeyAlgorithm.ECDSA_P256)

        val keyProvider = SSHClient().loadKeys(key.privateKeyPem, null, null)
        assertTrue(keyProvider.public.algorithm == "EC")
        assertEquals(key.publicKeyLine, opensshPublicKeyLine(keyProvider.public))

        val signed = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyProvider.private)
            update(SIGNED_DATA)
            sign()
        }
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyProvider.public)
            update(SIGNED_DATA)
            verify(signed)
        }
        assertTrue(verified)
    }

    @Test
    fun `the generated PKCS8 DER also parses directly through the plain JDK EC KeyFactory`() {
        val key = generateKeyPair(GeneratedKeyAlgorithm.ECDSA_P256)
        val der = Base64.getDecoder().decode(
            key.privateKeyPem
                .lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
        )

        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))

        assertEquals("EC", privateKey.algorithm)
    }

    private companion object {
        val SIGNED_DATA = "shellwave ecdsa round-trip".toByteArray()
    }
}
