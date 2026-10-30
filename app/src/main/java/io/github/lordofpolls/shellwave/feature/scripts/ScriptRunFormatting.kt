package io.github.lordofpolls.shellwave.feature.scripts

import io.github.lordofpolls.shellwave.core.crypto.BiometricAuthException

private const val TRUNCATION_MARKER = "\n…[truncated - output exceeded the capture limit]"

fun mark(text: String, truncated: Boolean): String =
    if (truncated) text + TRUNCATION_MARKER else text

/**
 * The runner never writes a secret param itself, but the remote command could have echoed one back
 * on stdout, so they are scrubbed before anything reaches Room.
 */
fun redact(text: String, secretValues: List<String>): String =
    secretValues.fold(text) { acc, secret -> acc.replace(secret, "[REDACTED]") }

/**
 * The app's own refusals ([IllegalStateException]) and the system's biometric prompt errors are
 * already written for a person. Anything else is a keystore or crypto message that doesn't say what
 * to do, and re-entering the secret reseals it.
 */
fun unlockFailureMessage(e: Exception): String {
    val detail = e.message
    if (detail != null && (e is IllegalStateException || e is BiometricAuthException)) return detail
    return "Couldn't unlock this host's saved credential. Edit the host and enter its password or key again." +
            detail?.let { " ($it)" }.orEmpty()
}

fun runOutcomeMessage(exitStatus: Int?, stdout: String, stderr: String): String {
    val output = stdout.ifBlank { stderr }.trim()
    return when {
        exitStatus != null && exitStatus != 0 -> "Exited with status $exitStatus.\n$output".trim()
        output.isNotEmpty() -> output
        exitStatus == null -> "Finished - exit status unknown."
        else -> "Finished successfully."
    }
}
