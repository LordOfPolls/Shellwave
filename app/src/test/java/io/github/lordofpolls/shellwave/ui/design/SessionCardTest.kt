package io.github.lordofpolls.shellwave.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatUptime] is the pure formatting behind `SessionCard`'s uptime line. The ticking Compose
 * state around it ([rememberUptimeText]) needs a device and is not covered here.
 */
class SessionCardTest {

    @Test
    fun `zero and sub-second elapsed shows seconds`() {
        assertEquals("0s", formatUptime(0))
        assertEquals("0s", formatUptime(999))
    }

    @Test
    fun `seconds only below one minute`() {
        assertEquals("12s", formatUptime(12_000))
        assertEquals("59s", formatUptime(59_000))
    }

    @Test
    fun `minutes and seconds below one hour`() {
        assertEquals("5m 30s", formatUptime(5 * 60_000L + 30_000L))
        assertEquals("1m 00s", formatUptime(60_000))
    }

    @Test
    fun `hours and minutes drops seconds entirely`() {
        assertEquals("2h 05m", formatUptime(2 * 3_600_000L + 5 * 60_000L))
        assertEquals("1h 00m", formatUptime(3_600_000L))
    }

    @Test
    fun `negative elapsed clamps to zero rather than a negative string`() {
        assertEquals("0s", formatUptime(-5_000))
    }
}
