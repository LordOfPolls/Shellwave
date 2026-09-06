package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalInputDeltaTest {
    @Test
    fun `bare backspace on the placeholder`() {
        assertEquals(InputDelta(1, ""), terminalInputDelta(original = " ", result = ""))
    }

    @Test
    fun `plain typing is a pure insert`() {
        assertEquals(InputDelta(0, "b"), terminalInputDelta(original = " a", result = " ab"))
    }

    @Test
    fun `autocorrect replaces the trailing chars in place`() {
        assertEquals(InputDelta(1, "lo "), terminalInputDelta(original = " helo", result = " hello "))
    }

    @Test
    fun `IME deletes several characters`() {
        assertEquals(InputDelta(3, ""), terminalInputDelta(original = " hello", result = " he"))
    }

    @Test
    fun `unchanged input`() {
        assertEquals(InputDelta(0, ""), terminalInputDelta(original = " ", result = " "))
    }

    @Test
    fun `backspace over an emoji doesn't split the surrogate pair`() {
        assertEquals(InputDelta(1, ""), terminalInputDelta(original = " 😀", result = " "))
    }

    @Test
    fun `emoji insert`() {
        assertEquals(InputDelta(0, "😀"), terminalInputDelta(original = " ", result = " 😀"))
    }

    @Test
    fun `replacing an emoji with one sharing a high surrogate`() {
        assertEquals(
            InputDelta(1, "😂"),
            terminalInputDelta(original = " 😀", result = " 😂"),
        )
    }

    @Test
    fun `delete spanning the placeholder is not charged twice`() {
        assertEquals(InputDelta(2, ""), terminalInputDelta(original = " ls", result = ""))
    }

    @Test
    fun `whole-buffer replacement`() {
        assertEquals(InputDelta(2, "x"), terminalInputDelta(original = " ab", result = "x"))
    }

    @Test
    fun `placeholder replaced without deletion is not charged a backspace`() {
        assertEquals(InputDelta(0, "x"), terminalInputDelta(original = " ", result = "x"))
    }
}
