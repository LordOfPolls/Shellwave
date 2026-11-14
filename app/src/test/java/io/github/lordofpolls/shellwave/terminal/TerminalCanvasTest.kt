package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCanvasTest {
    @Test
    fun `columns use the fractional cell width so the last one stays on screen`() {
        // The truncated width 21 would give 51 columns, two more than the renderer draws.
        assertEquals(49, terminalColumns(1080, 21.7f))
        // The truncated width 16 would give 67.
        assertEquals(66, terminalColumns(1080, 16.2f))
    }

    @Test
    fun `a box narrower than one cell still has a column`() {
        assertEquals(1, terminalColumns(5, 21.7f))
    }
}
