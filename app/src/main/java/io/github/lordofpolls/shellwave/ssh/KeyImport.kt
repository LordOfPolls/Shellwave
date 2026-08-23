package io.github.lordofpolls.shellwave.ssh

import android.content.ContentResolver
import android.net.Uri
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.security.PublicKey

fun readKeyText(resolver: ContentResolver, uri: Uri): String =
    resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: throw java.io.IOException("Could not open $uri")

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

fun opensshPublicKeyLine(publicKey: PublicKey, comment: String = "shellwave"): String {
    val blob = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(publicKey).compactData
    val b64 = java.util.Base64.getEncoder().encodeToString(blob)
    val type = KeyType.fromKey(publicKey).toString()
    return "$type $b64 $comment"
}
