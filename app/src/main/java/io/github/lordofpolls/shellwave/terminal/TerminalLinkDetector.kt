package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth
import java.util.regex.Pattern

/**
 * The vendored engine has no linkify logic, and `termux-shared`'s `TermuxUrlUtils` was never
 * vendored and only covers `scheme://` URLs anyway, so all three patterns here are written fresh.
 */
enum class LinkType { URL, IP, PATH }

data class TerminalLink(
    val type: LinkType,
    val text: String,
    val startCol: Int,
    val endCol: Int,
    val row: Int
)

// A practical subset in place of a ~30-scheme table: a match only ever goes to Intent.ACTION_VIEW,
// so any RFC 3986-shaped scheme is enough to be worth offering "Open" on.
private val URL_PATTERN: Pattern = Pattern.compile("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s<>"'`]+""")

// Loose: octets aren't range-checked. A false positive makes a non-address tappable, which Copy or
// a failed Open recovers from; a missed real IP is worse for a feature that exists to tap the IP
// you are looking at.
private val IP_PATTERN: Pattern =
    Pattern.compile("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?::\d{1,5})?\b""")

// Bare absolute paths. The lookbehind keeps it from firing mid-URL, leaving the "/foo" in
// "http://host/foo" to URL_PATTERN.
private val PATH_PATTERN: Pattern = Pattern.compile("""(?<![\w/])/[\w.\-_/]+""")

/**
 * Built once per row and shared across all three patterns rather than probed per-tap: the
 * char-index to column table is the expensive part; the regex is cheap.
 *
 * That table exists because `Matcher` reports boundaries as character indices, and a display column
 * only equals a character index when every character on the row is single-width. A one-shot walk
 * and not [validColumn]'s per-column probe, since every match needs converting.
 */
fun findLinksInRow(emulator: TerminalEmulator, row: Int): List<TerminalLink> {
    val lastCol = emulator.mColumns - 1
    val line = emulator.getSelectedText(0, row, lastCol, row)
    if (line.isBlank()) return emptyList()

    val columnAt = IntArray(line.length + 1)
    var column = 0
    var i = 0
    while (i < line.length) {
        columnAt[i] = column
        val ch1 = line[i]
        val width =
            if (Character.isHighSurrogate(ch1) && i + 1 < line.length) {
                val codePoint = Character.toCodePoint(ch1, line[i + 1])
                columnAt[i + 1] = column // the low surrogate shares its high surrogate's column
                i++
                WcWidth.width(codePoint)
            } else {
                WcWidth.width(ch1.code)
            }
        column += width.coerceAtLeast(0)
        i++
    }
    columnAt[line.length] = column

    val results = mutableListOf<TerminalLink>()
    fun collect(pattern: Pattern, type: LinkType) {
        val matcher = pattern.matcher(line)
        while (matcher.find()) {
            val startCol = columnAt[matcher.start()]
            val endCol = columnAt[matcher.end()] - 1
            if (endCol >= startCol) results += TerminalLink(
                type,
                matcher.group(),
                startCol,
                endCol,
                row
            )
        }
    }
    // URL first. A URL's own path segment also satisfies PATH_PATTERN, and linkAt takes the first
    // match containing the tapped column, so ordering picks the more specific classification.
    collect(URL_PATTERN, LinkType.URL)
    collect(IP_PATTERN, LinkType.IP)
    collect(PATH_PATTERN, LinkType.PATH)
    return results
}

fun linkAt(emulator: TerminalEmulator, row: Int, col: Int): TerminalLink? =
    findLinksInRow(emulator, row).firstOrNull { col in it.startCol..it.endCol }
