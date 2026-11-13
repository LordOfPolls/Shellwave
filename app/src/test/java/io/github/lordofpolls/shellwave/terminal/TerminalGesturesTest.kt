package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `fractional wheel scroll carries until it adds up to a notch`() {
        val (first, carry) = wheelNotches(0f, 0.4f)
        assertEquals(0, first)
        val (second, left) = wheelNotches(carry, 0.7f)
        assertEquals(1, second)
        assertEquals(0.1f, left, 0.001f)
    }

    @Test
    fun `wheel notches keep their sign and leave the fraction`() {
        assertEquals(1, wheelNotches(0f, 1.5f).first)
        assertEquals(0.5f, wheelNotches(0f, 1.5f).second, 0.001f)
        assertEquals(-2 to 0f, wheelNotches(0f, -2f))
    }

    @Test
    fun `no notches does nothing`() {
        assertNull(wheelAction(0, mouseTracking = true, alternateBuffer = true))
    }

    @Test
    fun `mouse tracking wins and sends one event per notch`() {
        assertEquals(
            WheelAction.MouseReport(TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, 2),
            wheelAction(2, mouseTracking = true, alternateBuffer = true),
        )
        assertEquals(
            WheelAction.MouseReport(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1),
            wheelAction(-1, mouseTracking = true, alternateBuffer = false),
        )
    }

    @Test
    fun `alternate buffer sends three arrows per notch`() {
        assertEquals(
            WheelAction.Arrows(down = true, count = 6),
            wheelAction(2, mouseTracking = false, alternateBuffer = true),
        )
        assertEquals(
            WheelAction.Arrows(down = false, count = 3),
            wheelAction(-1, mouseTracking = false, alternateBuffer = true),
        )
    }

    @Test
    fun `main screen scrolls three rows per notch, positive toward newer content`() {
        assertEquals(
            WheelAction.Scrollback(3),
            wheelAction(1, mouseTracking = false, alternateBuffer = false),
        )
        assertEquals(
            WheelAction.Scrollback(-6),
            wheelAction(-2, mouseTracking = false, alternateBuffer = false),
        )
    }
}
