package io.github.lordofpolls.shellwave.core.util

/**
 * Wraps [value] so a POSIX shell reads it as one literal token.
 *
 * The `'\''` dance closes the quote, emits an escaped quote outside it, and reopens: single quotes
 * cannot nest, and the four tokens concatenate with no whitespace between them.
 */
fun posixQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private val PLACEHOLDER_REGEX = Regex("\\{\\{(\\w+)\\}\\}")

/** First-occurrence order, which is the order the parameter prompt asks in. */
fun extractParamNames(snippet: String): List<String> =
    PLACEHOLDER_REGEX.findAll(snippet).map { it.groupValues[1] }.distinct().toList()

/**
 * Substitutes `{{name}}` placeholders, quoting every value. A script snippet runs verbatim on the
 * remote host and parameter values are arbitrary user text, so there is no raw mode: a placeholder
 * with no entry in [values] becomes an empty `''` instead of being left as literal `{{name}}`,
 * which the shell would then read as syntax.
 */
fun substituteParams(snippet: String, values: Map<String, String>): String =
    PLACEHOLDER_REGEX.replace(snippet) { match -> posixQuote(values[match.groupValues[1]].orEmpty()) }
