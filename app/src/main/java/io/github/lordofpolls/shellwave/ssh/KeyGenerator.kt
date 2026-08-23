package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.common.Buffer
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64

enum class GeneratedKeyAlgorithm { ED25519, RSA }

data class GeneratedKey(val privateKeyPem: String, val publicKeyLine: String)

/**
 * RSA exports as a plain PKCS8 PEM, `getEncoded()` already being PKCS8 DER.
 *
 * ed25519 cannot: sshj 0.40.0's `PKCS8KeyFile` knows only the DSA/EC/RSA OIDs and throws on a PKCS8
 * ed25519 key, surfacing as "exhausted available authentication methods" with no hint the key never
 * parsed. Those go out in OpenSSH's `openssh-key-v1` format, which sshj handles end to end.
 *
 * The PEM is unencrypted at the file level either way; VaultCrypto protects it at rest. An imported
 * key keeps whatever passphrase it arrived with.
 */
fun generateKeyPair(algorithm: GeneratedKeyAlgorithm): GeneratedKey {
    val keyPairGenerator =
        when (algorithm) {
            GeneratedKeyAlgorithm.ED25519 -> KeyPairGenerator.getInstance("Ed25519")
            GeneratedKeyAlgorithm.RSA -> KeyPairGenerator.getInstance("RSA")
                .apply { initialize(RSA_KEY_SIZE_BITS) }
        }
    val keyPair = keyPairGenerator.generateKeyPair()
    val pem =
        when (algorithm) {
            GeneratedKeyAlgorithm.ED25519 -> opensshV1PrivateKeyPem(keyPair)
            GeneratedKeyAlgorithm.RSA -> pkcs8Pem(keyPair.private)
        }
    return GeneratedKey(privateKeyPem = pem, publicKeyLine = opensshPublicKeyLine(keyPair.public))
}

private const val RSA_KEY_SIZE_BITS = 3072
private const val OPENSSH_V1_COMMENT = "shellwave"
private const val OPENSSH_V1_CIPHER_BLOCK_SIZE = 8
private val OPENSSH_V1_AUTH_MAGIC = "openssh-key-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

private fun pkcs8Pem(key: PrivateKey): String {
    val body = Base64.getEncoder().encodeToString(key.encoded).chunked(64).joinToString("\n")
    return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
}

/** The two pieces `openssh-key-v1` wants, neither exposed directly by the JCA key API. */
private fun ed25519RawKeyMaterial(keyPair: KeyPair): Pair<ByteArray, ByteArray> {
    val params =
        PrivateKeyFactory.createKey(PrivateKeyInfo.getInstance(keyPair.private.encoded)) as Ed25519PrivateKeyParameters
    return params.encoded to params.generatePublicKey().encoded
}

private fun opensshV1PrivateKeyPem(keyPair: KeyPair): String {
    val (seed, rawPublic) = ed25519RawKeyMaterial(keyPair)
    val publicKeyBlob = Buffer.PlainBuffer().putPublicKey(keyPair.public).compactData

    val checkint = SecureRandom().nextInt()
    val privateSection =
        Buffer.PlainBuffer()
            .putUInt32FromInt(checkint)
            .putUInt32FromInt(checkint)
            .putString("ssh-ed25519")
            .putString(rawPublic)
            .putString(seed + rawPublic)
            .putString(OPENSSH_V1_COMMENT)
    val unpaddedLength = privateSection.compactData.size
    val paddingLength =
        (OPENSSH_V1_CIPHER_BLOCK_SIZE - unpaddedLength % OPENSSH_V1_CIPHER_BLOCK_SIZE) % OPENSSH_V1_CIPHER_BLOCK_SIZE
    for (padByte in 1..paddingLength) privateSection.putByte(padByte.toByte())

    val outer =
        Buffer.PlainBuffer()
            .putRawBytes(OPENSSH_V1_AUTH_MAGIC)
            .putString("none")
            .putString("none")
            .putString(ByteArray(0))
            .putUInt32FromInt(1)
            .putString(publicKeyBlob)
            .putString(privateSection.compactData)

    val body = Base64.getEncoder().encodeToString(outer.compactData).chunked(70).joinToString("\n")
    return "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
}
