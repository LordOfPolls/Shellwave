package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class SessionLogSinkTest {

    // Throws unconditionally, including from the 3-arg overload BufferedOutputStream calls
    // directly once a write is at least as large as its internal buffer.
    private class FailingOutputStream : OutputStream() {
        var closed = false
        override fun write(b: Int) = throw IOException("disk full")
        override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `a successful write reaches the underlying stream once closed`() {
        val out = ByteArrayOutputStream()
        val sink = SessionLogSink(out) { }

        sink.write(byteArrayOf(1, 2, 3), 0, 3)
        sink.close()

        assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())
    }

    @Test
    fun `a write failure reports once and swallows a second failing write`() {
        val out = FailingOutputStream()
        var failures = 0
        val sink = SessionLogSink(out) { failures++ }
        // Larger than BufferedOutputStream's internal buffer, so this bypasses buffering and
        // reaches the underlying stream immediately instead of only failing on a later flush.
        val bigChunk = ByteArray(9000)

        sink.write(bigChunk, 0, bigChunk.size)
        sink.write(bigChunk, 0, bigChunk.size)

        assertEquals(1, failures)
        assertTrue(out.closed)
    }

    @Test
    fun `a write after a user-initiated close is a no-op, not a reported failure`() {
        val out = FailingOutputStream()
        var failures = 0
        val sink = SessionLogSink(out) { failures++ }

        sink.close()
        sink.write(byteArrayOf(1), 0, 1)

        assertEquals(0, failures)
    }
}
