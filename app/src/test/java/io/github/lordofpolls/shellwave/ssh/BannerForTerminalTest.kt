package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class BannerForTerminalTest {
    @Test
    fun normalisesEveryLineEndingToCrLf() {
        assertEquals("a\r\nb\r\nc\r\nd\r\n", bannerForTerminal("a\nb\r\nc\rd\n"))
    }

    @Test
    fun endsOnAFreshLineWithoutAddingABlankOne() {
        assertEquals("a\r\n", bannerForTerminal("a"))
        assertEquals("a\r\n", bannerForTerminal("a\n"))
    }

    @Test
    fun keepsBlankLinesAndTabsInsideTheBanner() {
        assertEquals("a\r\n\r\n\tb\r\n", bannerForTerminal("a\n\n\tb"))
    }

    @Test
    fun dropsC0DelAndC1ControlCharacters() {
        assertEquals("]52;c;aGk=\r\n", bannerForTerminal("\u001b]52;c;aGk=\u0007"))
        assertEquals("[31mred\r\n", bannerForTerminal("\u009b[31m\u007fred"))
    }

    @Test
    fun keepsNonAsciiText() {
        assertEquals("héllo 日本語 🐟\r\n", bannerForTerminal("héllo 日本語 🐟"))
    }

    @Test
    fun noBannerWritesNothing() {
        assertEquals("", bannerForTerminal(""))
        assertEquals("", bannerForTerminal(" \r\n\u0007\n"))
    }
}
