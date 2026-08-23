package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth

/**
 * External row coordinates, matching `topRow` in `TerminalCanvas`: row 0 is the live screen's top,
 * negative rows are scrollback. Not normalised by construction. [normalized] sorts the pair, which
 * callers need both for rendering and for [TerminalEmulator.getSelectedText], which assumes
 * `y1 <= y2`.
 */
data class TerminalSelection(
    val startCol: Int,
    val startRow: Int,
    val endCol: Int,
    val endRow: Int
) {
    fun normalized(): TerminalSelection =
        if (startRow > endRow || (startRow == endRow && startCol > endCol)) {
            TerminalSelection(endCol, endRow, startCol, startRow)
        } else {
            this
        }
}

/**
 * Expands a long-press at (col, row) to the word under it.
 *
 * Not built on `TerminalBuffer.getWordAtLocation`, which indexes a joined wrapped-line string with
 * `(row - y1) * columns + col`: a character index used as a display column, equivalent only when
 * every character is single-width. A wide (CJK) character before the touch column lands the offset
 * short, so long-pressing "好" in "你好世界 cjk" selects "你好". Upstream never calls that method, so the
 * bug is invisible there.
 *
 * This probes one column at a time through [TerminalEmulator.getSelectedText]. Either column of a
 * wide character returns the whole character, so walking outward to the first blank finds the true
 * edge with no width bookkeeping. Single-row only; cross-row joining is the part that breaks.
 */
fun wordSelectionAt(emulator: TerminalEmulator, col: Int, row: Int): TerminalSelection {
    val lastCol = emulator.mColumns - 1
    val clampedCol = validColumn(emulator, row, col.coerceIn(0, lastCol))
    if (emulator.getSelectedText(clampedCol, row, clampedCol, row).isBlank()) {
        return TerminalSelection(clampedCol, row, clampedCol, row)
    }

    var left = clampedCol
    var right = clampedCol
    while (left > 0 && emulator.getSelectedText(left - 1, row, left - 1, row).isNotBlank()) left--
    while (right < lastCol && emulator.getSelectedText(right + 1, row, right + 1, row)
            .isNotBlank()
    ) right++
    return TerminalSelection(left, row, right, row)
}

/**
 * Snaps a handle off the trailing half of a wide (CJK) character, which would otherwise select half
 * a cell and split the glyph.
 */
fun validColumn(emulator: TerminalEmulator, row: Int, targetCol: Int): Int {
    val line = emulator.screen.getSelectedText(0, row, targetCol, row)
    if (line.isEmpty()) return targetCol
    var column = 0
    var i = 0
    while (i < line.length) {
        val ch1 = line[i]
        if (ch1.code == 0) break
        val width =
            if (Character.isHighSurrogate(ch1) && i + 1 < line.length) {
                val ch2 = line[++i]
                WcWidth.width(Character.toCodePoint(ch1, ch2))
            } else {
                WcWidth.width(ch1.code)
            }
        val columnEnd = column + width
        if (targetCol > column && targetCol < columnEnd) return columnEnd
        if (columnEnd == column) return column
        column = columnEnd
        i++
    }
    return targetCol
}
