package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The editor saves in place, so a codec that changes a single byte it did not need to (a final
 * newline, a line ending) shows up as a spurious diff on the server, and one that lossily decodes
 * would corrupt the file on save.
 */
class RemoteTextCodecTest {

    private fun assertRoundTrips(bytes: ByteArray) {
        assertArrayEquals(bytes, RemoteTextCodec.encode(RemoteTextCodec.decode(bytes, 0L)))
    }

    private fun assertRefused(bytes: ByteArray) {
        try {
            RemoteTextCodec.decode(bytes, 0L)
            fail("expected RemoteFileNotText")
        } catch (_: RemoteFileNotText) {
        }
    }

    @Test
    fun `LF with a trailing newline round-trips`() {
        val decoded = RemoteTextCodec.decode("a\nb\n".toByteArray(), 7L)
        assertEquals("a\nb", decoded.text)
        assertEquals("\n", decoded.lineSeparator)
        assertTrue(decoded.trailingNewline)
        assertEquals(7L, decoded.mtime)
        assertRoundTrips("a\nb\n".toByteArray())
    }

    @Test
    fun `CRLF round-trips and is detected`() {
        val decoded = RemoteTextCodec.decode("a\r\nb\r\n".toByteArray(), 0L)
        assertEquals("a\nb", decoded.text)
        assertEquals("\r\n", decoded.lineSeparator)
        assertRoundTrips("a\r\nb\r\n".toByteArray())
    }

    @Test
    fun `no trailing newline round-trips`() {
        assertFalse(RemoteTextCodec.decode("a\nb".toByteArray(), 0L).trailingNewline)
        assertRoundTrips("a\nb".toByteArray())
        assertRoundTrips("a\r\nb".toByteArray())
    }

    @Test
    fun `empty file round-trips`() {
        assertRoundTrips(ByteArray(0))
    }

    @Test
    fun `a single newline round-trips`() {
        assertRoundTrips("\n".toByteArray())
    }

    @Test
    fun `a three-byte UTF-8 sequence round-trips`() {
        assertRoundTrips("price: €5\n".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `a NUL byte refuses`() {
        assertRefused(byteArrayOf(0x61, 0x00, 0x62))
    }

    @Test
    fun `a truncated multi-byte sequence refuses`() {
        assertRefused(byteArrayOf(0x61, 0xE2.toByte(), 0x82.toByte()))
    }

    @Test
    fun `a CRLF file edited with a bare LF line comes back all CRLF`() {
        val decoded = RemoteTextCodec.decode("a\r\nb\r\n".toByteArray(), 0L)
        val edited = decoded.copy(text = decoded.text + "\nc")
        assertEquals("a\r\nb\r\nc\r\n", RemoteTextCodec.encode(edited).toString(Charsets.UTF_8))
    }

    @Test
    fun `mixed line endings are treated as LF and round-trip byte-exact`() {
        val bytes = "a\r\nb\nc\r\n".toByteArray()
        val decoded = RemoteTextCodec.decode(bytes, 0L)
        assertEquals("\n", decoded.lineSeparator)
        assertArrayEquals(bytes, RemoteTextCodec.encode(decoded))
    }

    @Test
    fun `a CRLF pasted into a CRLF file is not doubled`() {
        val decoded = RemoteTextCodec.decode("a\r\nb\r\n".toByteArray(), 0L)
        val edited = decoded.copy(text = decoded.text + "\nx\r\ny")
        assertArrayEquals("a\r\nb\r\nx\r\ny\r\n".toByteArray(), RemoteTextCodec.encode(edited))
    }

    @Test
    fun `a double-converted CR CR LF file round-trips byte-exact`() {
        val bytes = "a\r\r\nb\r\n".toByteArray()
        val decoded = RemoteTextCodec.decode(bytes, 0L)
        assertEquals("\n", decoded.lineSeparator)
        assertArrayEquals(bytes, RemoteTextCodec.encode(decoded))
    }
}
