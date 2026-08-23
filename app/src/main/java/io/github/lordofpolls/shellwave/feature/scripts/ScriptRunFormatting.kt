package io.github.lordofpolls.shellwave.feature.scripts

private const val TRUNCATION_MARKER = "\n…[truncated - output exceeded the capture limit]"

fun mark(text: String, truncated: Boolean): String =
    if (truncated) text + TRUNCATION_MARKER else text

/**
 * The runner never writes a secret param itself, but the remote command could have echoed one back
 * on stdout, so they are scrubbed before anything reaches Room.
 */
fun redact(text: String, secretValues: List<String>): String =
    secretValues.fold(text) { acc, secret -> acc.replace(secret, "[REDACTED]") }
