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
 * Exactly two `Match` forms are handled, the same way as ssh_config itself: `Match host
 * <pattern-list>` (a single comma-separated argument, `!`-negatable per pattern) and `Match all`
 * (no argument). A `Match host` block applies to an alias if any of its patterns match that alias's
 * resolved `HostName` - falling back to the alias itself when no `Host` block set one - and its
 * directives take part in the same first-match-wins, file-order precedence as every `Host` block.
 * Any other `Match` line - a criterion this parser doesn't implement (`user`, `exec`, ...), or more
 * than one criterion (`Match host x user y`) - is not guessed at: the whole block is dropped, and
 * [ParsedSshConfig.matchNotes] says so instead of silently losing it.
 *
 * Not implemented, and surfaced and not dropped: `!pattern` negation on `Host` itself (a leading `!`
 * there is treated as a literal - only `Match host` negates), `ProxyJump`'s full `user@host:port` and
 * multi-hop grammar (a bare alias resolves, anything else is shown unresolved), and `Include`, where
 * SAF hands over one document URI and not a filesystem to resolve relative paths against, so
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

/** One `Host` or `Match` block as written: what makes it apply to a given alias, and every recognised key/value pair until the next `Host`/`Match` line. */
private sealed class ConfigBlock {
    val params = mutableListOf<Pair<String, String>>()
    abstract fun appliesTo(alias: String, hostName: String): Boolean
}

/** A `Host` block: matches purely on the alias, same as always - `hostName` is unused. */
private class HostBlock(val patterns: List<String>) : ConfigBlock() {
    override fun appliesTo(alias: String, hostName: String) = patterns.any { matchesHostPattern(it, alias) }
}

/**
 * `Match host <patterns>`: matches against the alias's already-resolved `HostName` (or the alias
 * itself, if none), not the alias - the one place this parser's `Host`/`Match` matching differs.
 * A leading `!` on a pattern negates it: if any negated pattern matches, the whole line does not
 * apply, regardless of the positive patterns.
 */
private class MatchHostBlock(patterns: List<String>) : ConfigBlock() {
    private val negated = patterns.filter { it.startsWith("!") }.map { it.substring(1) }
    private val positive = patterns.filterNot { it.startsWith("!") }

    override fun appliesTo(alias: String, hostName: String): Boolean {
        if (negated.any { matchesHostPattern(it, hostName) }) return false
        return positive.isEmpty() || positive.any { matchesHostPattern(it, hostName) }
    }
}

/** `Match all`: always applies. */
private class MatchAllBlock : ConfigBlock() {
    override fun appliesTo(alias: String, hostName: String) = true
}

/** A `Match` line this parser doesn't implement: never added to `blocks`, so its collected params are never read. */
private class IgnoredMatchBlock : ConfigBlock() {
    override fun appliesTo(alias: String, hostName: String) = false
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

/**
 * [hosts] in first-seen order; [includeDirectives] every `Include` argument found, in file order,
 * never followed - see class doc. [matchNotes] records one entry per `Match` block whose criterion
 * this parser does not implement, in file order, so the block is explained rather than silently
 * dropped.
 */
data class ParsedSshConfig(
    val hosts: List<ParsedSshHost>,
    val includeDirectives: List<String>,
    val matchNotes: List<String> = emptyList(),
)

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
    val matchNotes = mutableListOf<String>()
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
                val block = HostBlock(patterns)
                blocks += block
                current = block
                patterns.filterNot(::isWildcardPattern).forEach { aliasOrder += it }
            }

            key == "match" -> {
                // Exactly one criterion is supported: "all" with no argument, or "host" with a
                // single comma-separated pattern-list argument. Any other shape - an unsupported
                // criterion, or more than one criterion ("Match host x user y") - drops the block.
                val tokens = value.split(Regex("\\s+")).filter { it.isNotBlank() }
                current =
                    when {
                        tokens.size == 1 && tokens[0].equals("all", ignoreCase = true) ->
                            MatchAllBlock().also { blocks += it }

                        tokens.size == 2 && tokens[0].equals("host", ignoreCase = true) ->
                            MatchHostBlock(tokens[1].split(",").filter { it.isNotBlank() }).also { blocks += it }

                        else -> {
                            matchNotes += "Match $value: ignored (only 'Match host <pattern-list>' and 'Match all' are supported)"
                            IgnoredMatchBlock() // Not added to `blocks` - its directives go nowhere.
                        }
                    }
            }

            key == "include" -> {
                if (value.isNotEmpty()) includeDirectives += value
            }

            key in RECOGNIZED_KEYS -> {
                if (value.isEmpty()) continue // key with no value: malformed, ignore rather than throw.
                // A directive before any "Host"/"Match" line applies file-wide: treat it like an
                // implicit "Host *".
                val block = current ?: HostBlock(listOf("*")).also { blocks += it; current = it }
                block.params += key to value
            }

            else -> Unit // Unrecognised directive (Compression sub-options, etc.) - not this parser's concern.
        }
    }

    val hostBlocks = blocks.filterIsInstance<HostBlock>()
    val hosts = aliasOrder.map { alias -> resolveHost(alias, hostBlocks, blocks) }
    return ParsedSshConfig(hosts = hosts, includeDirectives = includeDirectives, matchNotes = matchNotes)
}

/**
 * `HostName` as `Host` blocks alone would resolve it - the value `Match host` blocks are matched
 * against. Computed from [hostBlocks] only, never from `Match` blocks: an alias's `HostName` isn't
 * allowed to depend on a `Match` block whose own applicability depends on that same `HostName`.
 */
private fun resolvedHostNameForMatching(alias: String, hostBlocks: List<HostBlock>): String =
    hostBlocks
        .filter { it.appliesTo(alias, "") }
        .firstNotNullOfOrNull { block -> block.params.firstOrNull { it.first == "hostname" }?.second }
        ?: alias

/** First-match-wins per key, `IdentityFile` cumulative - see this file's class doc. */
private fun resolveHost(alias: String, hostBlocks: List<HostBlock>, blocks: List<ConfigBlock>): ParsedSshHost {
    val hostName = resolvedHostNameForMatching(alias, hostBlocks)
    val matching = blocks.filter { it.appliesTo(alias, hostName) }

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
