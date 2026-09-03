package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator

/**
 * [row] indexes into the row list passed to [findMatches]. No column: nothing reads one yet, since
 * jumping to a match only needs the row - add one back when highlighting a match needs it.
 */
data class TerminalMatch(val row: Int)

/**
 * Case-insensitive substring search, one [TerminalMatch] per occurrence rather than per row, so two
 * hits on the same row still count as two for "n of m". Never spans a wrapped line: each row is
 * searched independently, matching how [collectTerminalRows] splits the buffer.
 */
fun findMatches(rows: List<String>, query: String): List<TerminalMatch> {
    if (query.isEmpty()) return emptyList()
    val needle = query.lowercase()
    val matches = mutableListOf<TerminalMatch>()
    rows.forEachIndexed { row, line ->
        val haystack = line.lowercase()
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx == -1) break
            matches += TerminalMatch(row)
            from = idx + 1
        }
    }
    return matches
}

/**
 * One row per external row from the top of scrollback to the bottom of the live screen, in the
 * same external coordinate system as `topRow` elsewhere in `terminal/` - so a match's list index
 * converts back to a scroll target with [externalRowAt].
 */
fun collectTerminalRows(emulator: TerminalEmulator): List<String> {
    val screen = emulator.screen
    val lastCol = emulator.mColumns - 1
    val startRow = -screen.activeTranscriptRows
    return (startRow until emulator.mRows).map { row ->
        screen.getSelectedText(0, row, lastCol, row)
    }
}

/** Converts a [collectTerminalRows] list index back to the external row [TerminalCanvas]'s `topRow` expects. */
fun externalRowAt(emulator: TerminalEmulator, rowIndex: Int): Int =
    -emulator.screen.activeTranscriptRows + rowIndex
