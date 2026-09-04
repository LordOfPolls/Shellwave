package io.github.lordofpolls.shellwave.ssh

import android.content.ContentResolver
import android.net.Uri
import com.hierynomus.sshj.userauth.certificate.Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.security.PublicKey
import java.util.Base64

/** A key or certificate file is a few KB at most; anything past this is refused rather than read into memory whole. */
private const val MAX_KEY_FILE_BYTES = 1 * 1024 * 1024

suspend fun readKeyText(resolver: ContentResolver, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Could not open $uri")
        stream.use {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = it.read(chunk)
                if (read == -1) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_KEY_FILE_BYTES) {
                    throw IOException("That file is larger than 1 MB - not a key or certificate")
                }
            }
            buffer.toString(Charsets.UTF_8.name())
        }
    }

/**
 * Returns the OpenSSH public-key line for the "confirm what you imported" step. The throwaway
 * SSHClient is used purely for `loadKeys`; no connection is made.
 *
 * The only choke point on the import path. `AddEditHostScreen.save` refuses to store a key it has
 * no parsed public line for, so a rejection here is a rejection everywhere.
 */
fun publicKeyLineOf(privateKeyPem: String, passphrase: String?): String {
    val passwordFinder = passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
    val keyProvider = SSHClient().loadKeys(privateKeyPem, null, passwordFinder)
    return opensshPublicKeyLine(keyProvider.public)
}

/**
 * Loads a private key together with the OpenSSH certificate that signs it (the `<name>-cert.pub`
 * file a user picks alongside their key), so the certificate - not the bare key - is what gets
 * offered to the server. sshj's `loadKeys(privateKey, publicKey, passwordFinder)` already accepts a
 * certificate's public-key line in place of a plain `.pub` line: `OpenSSHKeyFile` reports whatever
 * public key it was handed as `getPublic()`, and a `*-cert-v01@openssh.com` line parses to a
 * `com.hierynomus.sshj.userauth.certificate.Certificate`, not a bare key.
 *
 * Does not work for an ed25519 (`openssh-key-v1`) [privateKeyPem]: sshj 0.40.0's
 * `OpenSSHKeyV1KeyFile.init(String, String, PasswordFinder)` - the path that format goes through -
 * checks its own already-null `pubKey` field instead of the `publicKey` parameter it was just
 * handed, so [certificateText] is silently dropped and the bare key gets offered instead. RSA and
 * ECDSA go through `OpenSSHKeyFile`, whose equivalent override checks the parameter correctly. Not
 * yet root-caused past that - same shape as the keyboard-interactive bug noted in
 * `SshAuth.authenticate`.
 */
fun loadKeysWithCertificate(
    privateKeyPem: String,
    certificateText: String,
    passphrase: String?,
): KeyProvider {
    val passwordFinder = passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
    return SSHClient().loadKeys(privateKeyPem, certificateText, passwordFinder)
}

/**
 * True if [certificateLine] decodes to an OpenSSH certificate, with no private key to cross-check
 * against - the fallback when the stored key can't be decrypted right now (see AddEditHostScreen's
 * certificate picker for the biometric-unavailable case that needs this).
 */
fun parsesAsCertificate(certificateLine: String): Boolean {
    val parts = certificateLine.trim().split(Regex("\\s+"))
    if (parts.size < 2) return false
    return runCatching {
        Buffer.PlainBuffer(Base64.getDecoder().decode(parts[1])).readPublicKey() is Certificate<*>
    }.getOrDefault(false)
}

fun opensshPublicKeyLine(publicKey: PublicKey, comment: String = "shellwave"): String {
    val blob = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(publicKey).compactData
    val b64 = java.util.Base64.getEncoder().encodeToString(blob)
    val type = KeyType.fromKey(publicKey).toString()
    return "$type $b64 $comment"
}
