package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSearchTest {

    @Test
    fun `matches are case-insensitive and multiple per row are all found`() {
        val rows = listOf("Hello World", "hello there HELLO", "nothing here")

        val matches = findMatches(rows, "hello")

        assertEquals(
            listOf(TerminalMatch(0), TerminalMatch(1), TerminalMatch(1)),
            matches,
        )
    }

    @Test
    fun `empty query matches nothing`() {
        assertEquals(emptyList<TerminalMatch>(), findMatches(listOf("anything"), ""))
    }
}
