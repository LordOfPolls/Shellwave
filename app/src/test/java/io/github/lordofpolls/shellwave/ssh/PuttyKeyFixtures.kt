package io.github.lordofpolls.shellwave.ssh

import io.github.lordofpolls.shellwave.ssh.PuttyKeyFixtures.ed25519Key
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes `.ppk` files the way puttygen writes them - header block, AES-CBC body, MAC - so
 * [KeyImportPuttyTest] exercises the real envelope. A fixture built with sshj's own encoders would
 * agree with sshj's decoder by construction and prove nothing.
 *
 * Checked, not assumed: puttygen 0.85 reads back every shape emitted here and decrypts the
 * encrypted ones, which has caught two fixture bugs sshj tolerated - the `Key-Derivation` ordering
 * below, and [ed25519Key].
 *
 * Keys are generated inside the test run and never touch the repo. RSA carries the envelope cases,
 * with four mpints on both sides, so a failure there is the envelope. Ed25519 gets its own pair
 * because its blob is a bare 32-byte string whose meaning is the thing at issue.
 */
internal object PuttyKeyFixtures {

    const val COMMENT = "shellwave-test"

    fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /**
     * A complete `.ppk` for [keyPair]. [version] is the PPK format version (2 is the historical SHA-1
     * key derivation, 3 is Argon2); a non-null [passphrase] selects `aes256-cbc`, the only encryption
     * puttygen and sshj both support.
     */
    fun ppk(
        keyPair: KeyPair,
        version: Int,
        passphrase: String? = null,
        comment: String = COMMENT,
    ): String =
        ppkFrom(
            keyType = "ssh-rsa",
            publicBlob = publicBlob(keyPair.public as RSAPublicKey),
            privateKeyBlob = privateBlob(keyPair.private as RSAPrivateCrtKey),
            version = version,
            passphrase = passphrase,
            comment = comment,
        )

    /**
     * Shared by the RSA and Ed25519 fixtures so encryption, KDF and MAC are written once - the key
     * encodings are where algorithms differ, and nothing outside them does.
     */
    private fun ppkFrom(
        keyType: String,
        publicBlob: ByteArray,
        privateKeyBlob: ByteArray,
        version: Int,
        passphrase: String?,
        comment: String,
    ): String {
        require(version == 2 || version == 3) { "PuTTY key file version $version is not a thing" }

        val encryption = if (passphrase == null) "none" else "aes256-cbc"
        val kdfHeaders = LinkedHashMap<String, String>()

        // puttygen pads the private blob out to the cipher block size with random bytes before
        // encrypting, and the MAC covers the padded plaintext, so padding is settled first.
        val privateBlob =
            if (passphrase == null) privateKeyBlob else padToCipherBlock(privateKeyBlob)

        val macKey: ByteArray
        val body: ByteArray
        if (passphrase == null) {
            // v2's key is the real thing, SHA-1 over the tag and an empty passphrase. v3's is
            // zero-length, which SecretKeySpec rejects, so 32 zero bytes stand in - equivalent,
            // because HMAC zero-pads any under-length key out to its 64-byte block. puttygen
            // accepts these files and reports "MAC failed" once a digest byte is altered.
            macKey = if (version == 2) v2MacKey("") else ByteArray(32)
            body = privateBlob
        } else {
            val cipherKey: ByteArray
            val iv: ByteArray
            if (version == 2) {
                cipherKey = v2CipherKey(passphrase)
                iv = ByteArray(16)
                macKey = v2MacKey(passphrase)
            } else {
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                kdfHeaders["Key-Derivation"] = "Argon2id"
                kdfHeaders["Argon2-Memory"] = ARGON2_MEMORY.toString()
                kdfHeaders["Argon2-Passes"] = ARGON2_PASSES.toString()
                kdfHeaders["Argon2-Parallelism"] = ARGON2_PARALLELISM.toString()
                kdfHeaders["Argon2-Salt"] = hex(salt)
                // One Argon2 run yields 80 bytes that split key | iv | mac-key, so a v3 file needs
                // no separate MAC derivation step.
                val derived = argon2(passphrase, salt)
                cipherKey = derived.copyOfRange(0, 32)
                iv = derived.copyOfRange(32, 48)
                macKey = derived.copyOfRange(48, 80)
            }
            body = aes256CbcEncrypt(cipherKey, iv, privateBlob)
        }

        val mac =
            mac(
                algorithm = if (version == 2) "HmacSHA1" else "HmacSHA256",
                key = macKey,
                data = macData(keyType, encryption, comment, publicBlob, privateBlob),
            )

        return buildString {
            append("PuTTY-User-Key-File-$version: $keyType\n")
            append("Encryption: $encryption\n")
            append("Comment: $comment\n")
            appendBase64Block(this, "Public-Lines", publicBlob)
            // After Public-Lines rather than before. sshj reads the headers into a map and does not
            // care; puttygen 0.85 rejects the other order with "file format error", and a fixture
            // only the decoder under test accepts is the failure this file exists to avoid.
            kdfHeaders.forEach { (name, value) -> append("$name: $value\n") }
            appendBase64Block(this, "Private-Lines", body)
            append("Private-MAC: ${hex(mac)}\n")
        }
    }

    /**
     * An Ed25519 key as its two PuTTY-relevant parts. Not a JCA `KeyPair` because the fixture needs the
     * raw seed and the raw point, and no JCA type hands over either.
     */
    class Ed25519Key(val seed: ByteArray, val publicPoint: ByteArray)

    /**
     * PuTTY's PPK spec (appendix C) calls the Ed25519 private blob "the private exponent, which is the
     * discrete log of the public point", which reads as the clamped scalar. puttygen does not write
     * that: for both v2 and v3 the blob is the 32-byte RFC 8410 seed. Reading it as a scalar and
     * deriving a point does not reproduce the file's own `Public-Lines`; reading it as a seed
     * reproduces them exactly.
     *
     * Following the tool over the prose, because the tool writes the files users import. Taking the
     * wording literally is what briefly put a guard in [publicKeyLineOf] that refused perfectly good
     * keys.
     */
    fun ed25519Key(): Ed25519Key {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Ed25519Key(seed, Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)
    }

    /** The `.ppk` puttygen would write for [key] - the seed as a single ssh-string, nothing else. */
    fun ed25519Ppk(
        key: Ed25519Key,
        version: Int = 3,
        passphrase: String? = null,
        comment: String = COMMENT,
    ): String =
        ppkFrom(
            keyType = "ssh-ed25519",
            publicBlob = ed25519PublicKeyBlob(key),
            privateKeyBlob = ByteArrayOutputStream().apply { putSshString(this, key.seed) }
                .toByteArray(),
            version = version,
            passphrase = passphrase,
            comment = comment,
        )

    /** The OpenSSH public-key blob for [key], built independently of sshj. */
    fun ed25519PublicKeyBlob(key: Ed25519Key): ByteArray =
        ByteArrayOutputStream()
            .apply {
                putSshString(this, "ssh-ed25519")
                putSshString(this, key.publicPoint)
            }
            .toByteArray()

    // -- PuTTY's blob layouts ------------------------------------------------------------------

    private fun publicBlob(publicKey: RSAPublicKey): ByteArray =
        ByteArrayOutputStream()
            .apply {
                putSshString(this, "ssh-rsa")
                putSshMpint(this, publicKey.publicExponent)
                putSshMpint(this, publicKey.modulus)
            }
            .toByteArray()

    /** `d`, `p`, `q`, `iqmp` - Java's `crtCoefficient` is exactly PuTTY's `iqmp` (q inverse mod p). */
    private fun privateBlob(privateKey: RSAPrivateCrtKey): ByteArray =
        ByteArrayOutputStream()
            .apply {
                putSshMpint(this, privateKey.privateExponent)
                putSshMpint(this, privateKey.primeP)
                putSshMpint(this, privateKey.primeQ)
                putSshMpint(this, privateKey.crtCoefficient)
            }
            .toByteArray()

    /** Length-prefixed algorithm, encryption, comment, public blob, private blob - in that order. */
    private fun macData(
        keyType: String,
        encryption: String,
        comment: String,
        publicBlob: ByteArray,
        privateBlob: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                putSshString(this, keyType)
                putSshString(this, encryption)
                putSshString(this, comment)
                putSshString(this, publicBlob)
                putSshString(this, privateBlob)
            }
            .toByteArray()

    // -- key derivation ------------------------------------------------------------------------

    /** PPK v1/v2: two SHA-1 rounds over a sequence number and the passphrase, truncated to 32 bytes. */
    private fun v2CipherKey(passphrase: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = passphrase.toByteArray(Charsets.UTF_8)
        digest.update(byteArrayOf(0, 0, 0, 0))
        digest.update(bytes)
        val first = digest.digest()
        digest.update(byteArrayOf(0, 0, 0, 1))
        digest.update(bytes)
        val second = digest.digest()
        return first.copyOfRange(0, 20) + second.copyOfRange(0, 12)
    }

    private fun v2MacKey(passphrase: String): ByteArray =
        MessageDigest.getInstance("SHA-1").run {
            update("putty-private-key-file-mac-key".toByteArray(Charsets.UTF_8))
            update(passphrase.toByteArray(Charsets.UTF_8))
            digest()
        }

    private const val ARGON2_MEMORY = 8192
    private const val ARGON2_PASSES = 13
    private const val ARGON2_PARALLELISM = 1

    private fun argon2(passphrase: String, salt: ByteArray): ByteArray {
        val parameters =
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ARGON2_PASSES)
                .withMemoryAsKB(ARGON2_MEMORY)
                .withParallelism(ARGON2_PARALLELISM)
                .withSalt(salt)
                .build()
        return ByteArray(80).also {
            Argon2BytesGenerator()
                .apply { init(parameters) }
                .generateBytes(passphrase.toCharArray(), it)
        }
    }

    // -- primitives ----------------------------------------------------------------------------

    private fun padToCipherBlock(blob: ByteArray): ByteArray {
        val padded = blob.copyOf((blob.size + 15) / 16 * 16)
        if (padded.size > blob.size) {
            ByteArray(padded.size - blob.size)
                .also { SecureRandom().nextBytes(it) }
                .copyInto(padded, blob.size)
        }
        return padded
    }

    private fun aes256CbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(data)

    private fun mac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(algorithm)
            .apply { init(SecretKeySpec(key, algorithm)) }
            .doFinal(data)

    private fun putSshString(out: ByteArrayOutputStream, value: String) =
        putSshString(out, value.toByteArray(Charsets.UTF_8))

    private fun putSshString(out: ByteArrayOutputStream, value: ByteArray) {
        out.write(value.size ushr 24 and 0xFF)
        out.write(value.size ushr 16 and 0xFF)
        out.write(value.size ushr 8 and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value)
    }

    /** `BigInteger.toByteArray` is already minimal two's complement, which is the ssh mpint body. */
    private fun putSshMpint(out: ByteArrayOutputStream, value: BigInteger) =
        putSshString(out, value.toByteArray())

    private fun appendBase64Block(out: StringBuilder, header: String, blob: ByteArray) {
        val lines = java.util.Base64.getEncoder().encodeToString(blob).chunked(64)
        out.append("$header: ${lines.size}\n")
        lines.forEach { out.append(it).append('\n') }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
