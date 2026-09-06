package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalGesturesTest {
    @Test
    fun `drag travel splits into whole rows plus a carried remainder in both directions`() {
        assertEquals(1 to 5f, dragRows(25f, 20))
        assertEquals(-2 to -3f, dragRows(-43f, 20))
        assertEquals(0 to 19f, dragRows(19f, 20))
    }

    @Test
    fun `finger moving down wheels up, matching the local scrollback direction`() {
        assertEquals(TerminalEmulator.MOUSE_WHEELUP_BUTTON, wheelButton(1))
        assertEquals(TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, wheelButton(-1))
    }
}
