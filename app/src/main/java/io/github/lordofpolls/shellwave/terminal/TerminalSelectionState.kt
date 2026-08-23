package io.github.lordofpolls.shellwave.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.termux.terminal.TerminalEmulator

/** Coordinates throughout are [TerminalSelection]'s external row system, same as `topRow`. */
class TerminalSelectionState {
    var selection by mutableStateOf<TerminalSelection?>(null)
        private set

    // Sub-cell remainder of each handle's drag since its last full-cell step, so a slow drag still
    // eventually crosses a boundary instead of rounding to zero every event. Per-handle, so
    // dragging one doesn't consume the other's accumulated remainder.
    private var startRemainderPx = Offset.Zero
    private var endRemainderPx = Offset.Zero

    fun clear() {
        selection = null
    }

    fun selectWord(
        emulator: TerminalEmulator,
        cellWidthPx: Int,
        cellHeightPx: Int,
        topRow: Int,
        offset: Offset
    ) {
        val col = (offset.x / cellWidthPx).toInt().coerceIn(0, emulator.mColumns - 1)
        val screenRow = (offset.y / cellHeightPx).toInt().coerceIn(0, emulator.mRows - 1)
        startRemainderPx = Offset.Zero
        endRemainderPx = Offset.Zero
        selection = wordSelectionAt(emulator, col, screenRow + topRow)
    }

    fun selectAll(emulator: TerminalEmulator) {
        startRemainderPx = Offset.Zero
        endRemainderPx = Offset.Zero
        selection = TerminalSelection(
            0,
            -emulator.screen.activeTranscriptRows,
            emulator.mColumns - 1,
            emulator.mRows - 1
        )
    }

    fun shiftForScroll(scrolled: Int) {
        selection = selection?.let {
            it.copy(
                startRow = it.startRow - scrolled,
                endRow = it.endRow - scrolled
            )
        }
    }

    fun selectedText(emulator: TerminalEmulator): String? {
        val sel = selection?.normalized() ?: return null
        return emulator.getSelectedText(sel.startCol, sel.startRow, sel.endCol, sel.endRow)
    }

    /**
     * A handle is clamped so it cannot cross the other: dragging start past end holds it at end's
     * position instead of swapping which handle is "start". [validColumn] then snaps the landing column
     * off the trailing half of a wide character.
     */
    fun dragHandle(
        emulator: TerminalEmulator,
        cellWidthPx: Int,
        cellHeightPx: Int,
        isStart: Boolean,
        dx: Float,
        dy: Float
    ) {
        val sel = selection ?: return
        val remainder = if (isStart) startRemainderPx else endRemainderPx
        val newRemainder = Offset(remainder.x + dx, remainder.y + dy)
        val colDelta = (newRemainder.x / cellWidthPx).toInt()
        val rowDelta = (newRemainder.y / cellHeightPx).toInt()
        if (colDelta == 0 && rowDelta == 0) {
            if (isStart) startRemainderPx = newRemainder else endRemainderPx = newRemainder
            return
        }
        val settled = Offset(
            newRemainder.x - colDelta * cellWidthPx,
            newRemainder.y - rowDelta * cellHeightPx
        )
        if (isStart) startRemainderPx = settled else endRemainderPx = settled

        val minRow = -emulator.screen.activeTranscriptRows
        val maxRow = emulator.mRows - 1
        val maxCol = emulator.mColumns - 1

        selection =
            if (isStart) {
                var row = (sel.startRow + rowDelta).coerceIn(minRow, maxRow)
                var col = (sel.startCol + colDelta).coerceIn(0, maxCol)
                if (row > sel.endRow) row = sel.endRow
                if (row == sel.endRow && col > sel.endCol) col = sel.endCol
                sel.copy(startRow = row, startCol = validColumn(emulator, row, col))
            } else {
                var row = (sel.endRow + rowDelta).coerceIn(minRow, maxRow)
                var col = (sel.endCol + colDelta).coerceIn(0, maxCol)
                if (row < sel.startRow) row = sel.startRow
                if (row == sel.startRow && col < sel.startCol) col = sel.startCol
                sel.copy(endRow = row, endCol = validColumn(emulator, row, col))
            }
    }
}

@Composable
fun rememberTerminalSelectionState(): TerminalSelectionState = remember { TerminalSelectionState() }
