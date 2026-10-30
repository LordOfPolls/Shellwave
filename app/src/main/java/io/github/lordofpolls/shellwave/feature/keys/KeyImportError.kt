package io.github.lordofpolls.shellwave.feature.keys

import com.hierynomus.sshj.common.KeyDecryptionFailedException

/**
 * sshj has no typed failure for "not a key file" and PuTTY reports a bad passphrase as a plain
 * "Invalid passphrase", so the text is matched. The raw message stays in brackets so a case this
 * misses is still diagnosable.
 */
internal fun keyImportErrorMessage(failure: Throwable): String {
    val raw = failure.message
    val advice = when {
        failure is KeyDecryptionFailedException || raw?.contains("passphrase", ignoreCase = true) == true ||
            raw?.contains("password", ignoreCase = true) == true ->
            "Wrong passphrase, or this key needs one. Check it and try again."
        raw?.contains("No provider available", ignoreCase = true) == true ->
            "That isn't a private key file. Pick an OpenSSH, PEM or PuTTY .ppk key, not the .pub half."
        else -> null
    }
    return when {
        advice == null -> raw ?: "Could not store the key"
        raw == null -> advice
        else -> "$advice [$raw]"
    }
}
