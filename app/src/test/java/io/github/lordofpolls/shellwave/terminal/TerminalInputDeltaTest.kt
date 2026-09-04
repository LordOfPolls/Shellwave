package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [terminalInputDelta] used to also cover a reset-race (fast typing outrunning the placeholder
 * reset); that race no longer exists now the transformation runs synchronously in the same edit.
 */
class TerminalInputDeltaTest {
    @Test
    fun `plain insert appends onto the placeholder`() {
        assertEquals(InputDelta.Text("h"), terminalInputDelta(original = " ", result = " h"))
    }

    @Test
    fun `multi-char insert`() {
        assertEquals(InputDelta.Text("hello"), terminalInputDelta(original = " ", result = " hello"))
    }

    @Test
    fun `delete to empty is backspace`() {
        assertEquals(InputDelta.Backspace, terminalInputDelta(original = " ", result = ""))
    }

    @Test
    fun `no-op space to space`() {
        assertEquals(InputDelta.None, terminalInputDelta(original = " ", result = " "))
    }

    @Test
    fun `whole-buffer replacement not prefixed by the placeholder`() {
        assertEquals(InputDelta.Text("xyz"), terminalInputDelta(original = " ", result = "xyz"))
    }

    @Test
    fun `newline`() {
        assertEquals(InputDelta.Text("\n"), terminalInputDelta(original = " ", result = " \n"))
    }

    @Test
    fun `surrogate pair insert intact`() {
        val emoji = "😀"
        assertEquals(InputDelta.Text(emoji), terminalInputDelta(original = " ", result = " $emoji"))
    }
}
