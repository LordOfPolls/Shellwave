package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.common.Buffer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey as EcPrivateKeyAsn1
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X962Parameters
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

enum class GeneratedKeyAlgorithm { ED25519, RSA, ECDSA_P256 }

data class GeneratedKey(val privateKeyPem: String, val publicKeyLine: String)

/**
 * RSA exports as a plain PKCS8 PEM, `getEncoded()` already being PKCS8 DER. ECDSA also goes out as
 * PKCS8, but its DER needs hand-assembly first - see [ecPkcs8WithoutInnerParams] for why.
 *
 * ed25519 cannot go out as PKCS8: `PKCS8KeyFile` throws on a PKCS8 ed25519 key, surfacing as
 * "exhausted available authentication methods" with no hint the key never parsed. Those go out in
 * OpenSSH's `openssh-key-v1` format, which sshj handles end to end.
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
            GeneratedKeyAlgorithm.ECDSA_P256 -> KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
        }
    val keyPair = keyPairGenerator.generateKeyPair()
    val pem =
        when (algorithm) {
            GeneratedKeyAlgorithm.ED25519 -> opensshV1PrivateKeyPem(keyPair)
            GeneratedKeyAlgorithm.RSA -> pkcs8Pem(keyPair.private.encoded)
            GeneratedKeyAlgorithm.ECDSA_P256 -> pkcs8Pem(ecPkcs8WithoutInnerParams(keyPair))
        }
    return GeneratedKey(privateKeyPem = pem, publicKeyLine = opensshPublicKeyLine(keyPair.public))
}

private const val RSA_KEY_SIZE_BITS = 3072
private const val OPENSSH_V1_COMMENT = "shellwave"
private const val OPENSSH_V1_CIPHER_BLOCK_SIZE = 8
private val OPENSSH_V1_AUTH_MAGIC = "openssh-key-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

private fun pkcs8Pem(encoded: ByteArray): String {
    val body = Base64.getEncoder().encodeToString(encoded).chunked(64).joinToString("\n")
    return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
}

/**
 * Neither the JDK's default "EC" provider's PKCS8 encoding nor BouncyCastle's own
 * `PrivateKeyInfoFactory` helper produces what sshj 0.40.0's `PKCS8KeyFile` needs here: it reads the
 * inner `ECPrivateKey` ASN.1 sequence's public key unconditionally from index 2, which is only where
 * it lands when the optional `[0] parameters` field is *absent* (the curve is already carried in the
 * outer PKCS8 `AlgorithmIdentifier`) - the JDK's encoding omits `[1] publicKey` entirely, and BC's
 * factory always writes both `[0]` and `[1]`. Built by hand so the inner sequence is exactly
 * `[version, privateKey, [1] publicKey]`, the shape sshj expects.
 */
private fun ecPkcs8WithoutInnerParams(keyPair: KeyPair): ByteArray {
    val publicKey = keyPair.public as ECPublicKey
    val privateKey = keyPair.private as ECPrivateKey
    val fieldSize = (publicKey.params.curve.field.fieldSize + 7) / 8
    val point =
        byteArrayOf(0x04) +
                fixedLengthBytes(publicKey.w.affineX, fieldSize) +
                fixedLengthBytes(publicKey.w.affineY, fieldSize)
    val orderBitLength = publicKey.params.order.bitLength()
    val innerKey = EcPrivateKeyAsn1(orderBitLength, privateKey.s, DERBitString(point), null)
    val algorithmId = AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, X962Parameters(SECObjectIdentifiers.secp256r1))
    return PrivateKeyInfo(algorithmId, innerKey).encoded
}

private fun fixedLengthBytes(value: BigInteger, length: Int): ByteArray {
    val raw = value.toByteArray()
    val trimmed = if (raw.size > length) raw.copyOfRange(raw.size - length, raw.size) else raw
    return ByteArray(length - trimmed.size) + trimmed
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
