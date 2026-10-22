package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator

class AgentKeyResolutionTest {
    // The cert-form blob starts with the `-cert-v01@openssh.com` type, which the agent refuses to sign,
    // so the blob has to be the bare key's even when a valid certificate is stored beside it.
    @Test
    fun certificateIsIgnoredAndBlobIsTheBareKey() {
        val generated = generateKeyPair(GeneratedKeyAlgorithm.ECDSA_P256)
        val ca = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val certificate = KeyImportCertificateTest().signCertificate(
            generated.publicKeyLine,
            opensshPublicKeyLine(ca.public),
            ca.private,
        )

        val key = agentKeyFrom(AuthMethod.PrivateKey(generated.privateKeyPem, null, certificate), "laptop")

        val expected = Buffer.PlainBuffer().putPublicKey(parsePublicKeyLine(generated.publicKeyLine)!!).compactData
        assertArrayEquals(expected, key.blob)
        assertEquals("ecdsa-sha2-nistp256", Buffer.PlainBuffer(key.blob).readString())
        assertEquals("laptop", key.label)
    }
}
