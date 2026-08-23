package io.github.lordofpolls.shellwave.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One test per branch of [parseQuickConnect]: user present or absent, port present, absent or
 * invalid, and the blank and host-less rejections.
 */
class QuickConnectTest {

    @Test
    fun `full form parses user, host and port`() {
        assertEquals(
            QuickConnectTarget("alice", "example.com", 2222),
            parseQuickConnect("alice@example.com:2222")
        )
    }

    @Test
    fun `missing user defaults to root`() {
        assertEquals(
            QuickConnectTarget("root", "example.com", 2222),
            parseQuickConnect("example.com:2222")
        )
    }

    @Test
    fun `missing port defaults to 22`() {
        assertEquals(
            QuickConnectTarget("alice", "example.com", 22),
            parseQuickConnect("alice@example.com")
        )
    }

    @Test
    fun `missing both user and port uses both defaults`() {
        assertEquals(
            QuickConnectTarget("root", "example.com", 22),
            parseQuickConnect("example.com")
        )
    }

    @Test
    fun `a non-numeric port falls back to 22`() {
        assertEquals(
            QuickConnectTarget("alice", "example.com", 22),
            parseQuickConnect("alice@example.com:not-a-port")
        )
    }

    @Test
    fun `blank user before the at-sign falls back to root`() {
        assertEquals(
            QuickConnectTarget("root", "example.com", 22),
            parseQuickConnect("@example.com")
        )
    }

    @Test
    fun `blank input returns null`() {
        assertNull(parseQuickConnect(""))
        assertNull(parseQuickConnect("   "))
    }

    @Test
    fun `host-less input returns null`() {
        assertNull(parseQuickConnect("alice@"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            QuickConnectTarget("alice", "example.com", 22),
            parseQuickConnect("  alice@example.com  ")
        )
    }

    @Test
    fun `an IPv6-shaped host splits on the last colon`() {
        // lastIndexOf(':') is deliberate: a bracket-less IPv6 literal has colons of its own, so
        // only the trailing one is treated as the port separator.
        val result = parseQuickConnect("root@::1:22")
        assertEquals(22, result?.port)
    }
}
