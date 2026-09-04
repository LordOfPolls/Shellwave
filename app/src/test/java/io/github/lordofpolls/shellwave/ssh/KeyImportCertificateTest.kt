package io.github.lordofpolls.shellwave.ssh

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * Pure logic, no device dependency. Both the subject key and the CA that signs its certificate are
 * generated fresh for this test - never a checked-in fixture.
 *
 * The subject key is ECDSA, not ed25519 - see [loadKeysWithCertificate]'s KDoc for why.
 *
 * There is no OpenSSH certificate writer in sshj 0.40.0 (only a reader), so the certificate below is
 * assembled by hand from the wire format `net.schmizz.sshj.common.KeyType`'s `*_CERT` variants read -
 * see that class's `CertUtils.readPubKey`/`writePubKeyContentsIntoBuffer` for the field order this
 * mirrors.
 */
class KeyImportCertificateTest {

    @Test
    fun `a key plus a certificate for it yields a KeyProvider whose public key is a Certificate`() {
        val subject = generateKeyPair(GeneratedKeyAlgorithm.ECDSA_P256)
        val ca = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val caPublicKeyLine = opensshPublicKeyLine(ca.public)

        val certificateText = signCertificate(subject.publicKeyLine, caPublicKeyLine, ca.private)

        val keyProvider = loadKeysWithCertificate(subject.privateKeyPem, certificateText, null)

        assertTrue(keyProvider.public is Certificate<*>)
    }

    @Test
    fun `AuthMethod PrivateKey with a certificate reaches loadKeysWithCertificate via the keyProviderFor seam`() {
        val subject = generateKeyPair(GeneratedKeyAlgorithm.ECDSA_P256)
        val ca = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val certificateText = signCertificate(subject.publicKeyLine, opensshPublicKeyLine(ca.public), ca.private)

        val keyProvider = keyProviderFor(AuthMethod.PrivateKey(subject.privateKeyPem, null, certificateText))

        assertTrue(keyProvider.public is Certificate<*>)
    }

    @Test
    fun `AuthMethod PrivateKey with no certificate yields the bare key`() {
        val subject = generateKeyPair(GeneratedKeyAlgorithm.ECDSA_P256)

        val keyProvider = keyProviderFor(AuthMethod.PrivateKey(subject.privateKeyPem, null, null))

        assertTrue(keyProvider.public !is Certificate<*>)
    }

    @Test
    fun `parsesAsCertificate is true for a signed certificate, false for a bare public key and for blank input`() {
        val subject = generateKeyPair(GeneratedKeyAlgorithm.ECDSA_P256)
        val ca = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val certificateText = signCertificate(subject.publicKeyLine, opensshPublicKeyLine(ca.public), ca.private)

        assertTrue(parsesAsCertificate(certificateText))
        assertFalse(parsesAsCertificate(subject.publicKeyLine))
        assertFalse(parsesAsCertificate(""))
        assertFalse(parsesAsCertificate("just-one-token"))
    }

    /** Everything after the leading key-type field of an `openssh` public-key line's blob, verbatim - see class doc. */
    private fun contentAfterTypeString(opensshLine: String): ByteArray {
        val blob = Base64.getDecoder().decode(opensshLine.split(" ")[1])
        val buffer = Buffer.PlainBuffer(blob)
        buffer.readString() // key-type field, not needed here
        val remaining = ByteArray(buffer.available())
        buffer.readRawBytes(remaining)
        return remaining
    }

    /** Builds and signs an `ecdsa-sha2-nistp256-cert-v01@openssh.com` user certificate for [subjectPublicKeyLine]. */
    private fun signCertificate(
        subjectPublicKeyLine: String,
        caPublicKeyLine: String,
        caPrivateKey: java.security.PrivateKey,
    ): String {
        val certType = "ecdsa-sha2-nistp256-cert-v01@openssh.com"
        val caBlob = Base64.getDecoder().decode(caPublicKeyLine.split(" ")[1])

        val toBeSigned =
            Buffer.PlainBuffer()
                .putString(certType)
                .putBytes(ByteArray(16)) // nonce
                .putRawBytes(contentAfterTypeString(subjectPublicKeyLine)) // curve name + point, verbatim
                .putUInt64(1L) // serial
                .putUInt32(1L) // SSH_CERT_TYPE_USER
                .putString("shellwave-test-cert") // key id
                .putBytes(ByteArray(0)) // valid principals: empty = any
                .putUInt64(0L) // valid after
                .putUInt64(Long.MAX_VALUE) // valid before
                .putBytes(ByteArray(0)) // critical options
                .putBytes(ByteArray(0)) // extensions
                .putString("") // reserved
                .putBytes(caBlob) // signature key

        val signature =
            Signature.getInstance("Ed25519").apply {
                initSign(caPrivateKey)
                update(toBeSigned.compactData)
            }.sign()

        val certBlob = toBeSigned.putSignature("ssh-ed25519", signature).compactData
        return "$certType ${Base64.getEncoder().encodeToString(certBlob)} shellwave-test"
    }
}
