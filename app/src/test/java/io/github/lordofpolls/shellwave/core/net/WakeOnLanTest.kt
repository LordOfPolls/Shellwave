package io.github.lordofpolls.shellwave.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun parsesTheThreeSeparatorStyles() {
        val expected = byteArrayOf(0x3C, 0x22, 0xFB.toByte(), 0x01, 0x02, 0x03)
        assertArrayEquals(expected, parseMacAddress("3c:22:fb:01:02:03"))
        assertArrayEquals(expected, parseMacAddress("3C-22-FB-01-02-03"))
        assertArrayEquals(expected, parseMacAddress("3c22fb010203"))
        assertArrayEquals(expected, parseMacAddress("  3c:22:fb:01:02:03  "))
    }

    @Test
    fun rejectsAnythingThatIsNotSixHexOctets() {
        assertNull(parseMacAddress(""))
        assertNull(parseMacAddress("3c:22:fb:01:02"))
        assertNull(parseMacAddress("3c:22:fb:01:02:03:04"))
        // The trap case: right length, wrong alphabet. `g` is not hex.
        assertNull(parseMacAddress("3c:22:fb:01:02:0g"))
        assertNull(parseMacAddress("not a mac"))
    }

    @Test
    fun magicPacketIsSixFfBytesThenTheMacSixteenTimes() {
        val packet = magicPacket("3c:22:fb:01:02:03")!!
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        val mac = parseMacAddress("3c:22:fb:01:02:03")!!
        for (repeat in 0 until 16) {
            assertArrayEquals(
                "repeat $repeat",
                mac,
                packet.copyOfRange(6 + repeat * 6, 12 + repeat * 6),
            )
        }
    }

    @Test
    fun magicPacketRefusesAnInvalidMac() {
        assertNull(magicPacket("not a mac"))
    }
}
