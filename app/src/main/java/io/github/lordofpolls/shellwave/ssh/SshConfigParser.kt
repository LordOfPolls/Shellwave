package io.github.lordofpolls.shellwave.ssh

/**
 * A `~/.ssh/config` parser, since sshj has none. Text in, data out - no file I/O, no Room, no
 * `android.*` - so it is unit testable and so ImportSshConfigScreen stays the only place a parsed
 * entry becomes a row.
 *
 * Handles `Host`, `HostName`, `User`, `Port`, `IdentityFile`, `ProxyJump`, `Compression`,
 * `ServerAliveInterval` and `Include`; ignores the rest. Keys are case-insensitive and
 * first-match-wins per key in file order, the way OpenSSH resolves a target, with a bare directive
 * before the first `Host` line treated as an implicit `Host *`. `IdentityFile` is the exception:
 * real ssh_config accumulates every line that applies. A `Host` pattern containing `*` or `?`
 * matches, but is not itself importable.
 *
 * Not implemented, and surfaced and not dropped: `!pattern` negation (a leading `!` is treated as a
 * literal), `ProxyJump`'s full `user@host:port` and multi-hop grammar (a bare alias resolves,
 * anything else is shown unresolved), `Match` blocks (TODO if anyone asks for them), and `Include`,
 * where SAF hands over one document URI and not a filesystem to resolve relative paths against, so
 * ParsedSshConfig.includeDirectives lets the screen explain the short host list.
 *
 * [ParsedSshHost.identityFiles] holds paths, never key material: nothing here opens a file an
 * `IdentityFile` names. Key material still enters only through CredentialVault and the SAF/paste
 * flow.
 */
private val RECOGNIZED_KEYS = setOf(
    "hostname",
    "user",
    "port",
    "identityfile",
    "proxyjump",
    "compression",
    "serveraliveinterval"
)

/** One `Host` block as written: the pattern(s) on its `Host` line, and every recognised key/value pair until the next `Host` line. */
private class ConfigBlock(val patterns: List<String>) {
    val params = mutableListOf<Pair<String, String>>()
}

/**
 * One literal (non-wildcard) `Host` alias found anywhere in the file, with its effective
 * configuration already resolved across every matching block - the caller never needs to know about
 * blocks or pattern matching at all.
 *
 * [hostName] falls back to [alias] itself when no `HostName` directive matched, exactly like real
 * ssh (an alias with no explicit `HostName` connects to itself).
 *
 * [compression] and [serverAliveInterval] are carried through purely for the preview screen to
 * display: there is no per-host compression or keepalive-interval setting for either to write into,
 * and no schema is invented for them. The import screen surfaces them as parsed-but-not-applied.
 */
data class ParsedSshHost(
    val alias: String,
    val hostName: String,
    val user: String?,
    val port: Int?,
    /** Raw paths as written in the file, for display only - never read from disk. */
    val identityFiles: List<String>,
    /** Raw `ProxyJump` value as written - an alias reference, a literal `user@host`, or a comma-list; only the plain-alias case is resolved by the caller. */
    val proxyJump: String?,
    val compression: Boolean?,
    val serverAliveInterval: Int?,
)

/** [hosts] in first-seen order; [includeDirectives] every `Include` argument found, in file order, never followed - see class doc. */
data class ParsedSshConfig(val hosts: List<ParsedSshHost>, val includeDirectives: List<String>)

/** True if [pattern] contains an ssh_config glob character (`*` or `?`) and therefore can never itself be a literal, connectable alias. */
private fun isWildcardPattern(pattern: String): Boolean =
    pattern.contains('*') || pattern.contains('?')

/** Matches [alias] against a `Host` [pattern] - exact string equality for a literal pattern, `*`/`?` glob semantics otherwise. Case-sensitive, matching real ssh_config `Host` matching. */
private fun matchesHostPattern(pattern: String, alias: String): Boolean {
    if (!isWildcardPattern(pattern)) return pattern == alias
    val regex =
        buildString {
            append('^')
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }
    return Regex(regex).matches(alias)
}

/** Never throws on malformed input: an unrecognised or value-less line is simply ignored. */
fun parseSshConfig(text: String): ParsedSshConfig {
    val blocks = mutableListOf<ConfigBlock>()
    val includeDirectives = mutableListOf<String>()
    val aliasOrder = LinkedHashSet<String>()
    var current: ConfigBlock? = null

    for (rawLine in text.lineSequence()) {
        // Trailing "# comment" is stripped unconditionally (not quote-aware) - a deliberate
        // simplification; real-world config values essentially never contain a literal '#'.
        val line = rawLine.substringBefore('#').trim()
        if (line.isEmpty()) continue

        val parts = line.split(Regex("[\\s=]+"), limit = 2)
        val key = parts[0].lowercase()
        val value = parts.getOrElse(1) { "" }.trim()

        when {
            key == "host" -> {
                if (value.isEmpty()) continue // "Host" with no pattern: malformed, ignore rather than throw.
                val patterns = value.split(Regex("\\s+")).filter { it.isNotBlank() }
                val block = ConfigBlock(patterns)
                blocks += block
                current = block
                patterns.filterNot(::isWildcardPattern).forEach { aliasOrder += it }
            }

            key == "include" -> {
                if (value.isNotEmpty()) includeDirectives += value
            }

            key in RECOGNIZED_KEYS -> {
                if (value.isEmpty()) continue // key with no value: malformed, ignore rather than throw.
                // A directive before any "Host" line applies file-wide: treat it like an implicit
                // "Host *".
                val block = current ?: ConfigBlock(listOf("*")).also { blocks += it; current = it }
                block.params += key to value
            }

            else -> Unit // Unrecognised directive (Match, Compression sub-options, etc.) - not this parser's concern.
        }
    }

    val hosts = aliasOrder.map { alias -> resolveHost(alias, blocks) }
    return ParsedSshConfig(hosts = hosts, includeDirectives = includeDirectives)
}

/** First-match-wins per key, `IdentityFile` cumulative - see this file's class doc. */
private fun resolveHost(alias: String, blocks: List<ConfigBlock>): ParsedSshHost {
    val matching = blocks.filter { block -> block.patterns.any { matchesHostPattern(it, alias) } }

    fun firstValue(key: String): String? =
        matching.firstNotNullOfOrNull { block -> block.params.firstOrNull { it.first == key }?.second }

    val identityFiles = matching.flatMap { block ->
        block.params.filter { it.first == "identityfile" }.map { it.second }
    }

    return ParsedSshHost(
        alias = alias,
        hostName = firstValue("hostname") ?: alias,
        user = firstValue("user"),
        port = firstValue("port")?.toIntOrNull(),
        identityFiles = identityFiles,
        proxyJump = firstValue("proxyjump"),
        compression = firstValue("compression")?.equals("yes", ignoreCase = true),
        serverAliveInterval = firstValue("serveraliveinterval")?.toIntOrNull(),
    )
}
