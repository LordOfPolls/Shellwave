package io.github.lordofpolls.shellwave.ssh

import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * A session log's destination stream, written raw - escape sequences included, since that is what
 * a session log is. Buffered so the reader loop isn't doing a SAF write syscall per shell output
 * chunk; flushed only on [close], not per [write].
 *
 * A write (or flush/close) failure closes the stream and reports once via [onFailure] - a full disk
 * or a revoked SAF grant must not spam the caller, and must never take the session down with it.
 * [write] and [close] are `@Synchronized` on the same monitor and both gate on [closed], so a
 * deliberate [close] racing an in-flight [write] can never turn into a reported failure: whichever
 * gets the lock first decides, and the loser sees [closed] already true.
 */
class SessionLogSink(
    out: OutputStream,
    private val onFailure: () -> Unit,
) {
    private val buffered = BufferedOutputStream(out)
    private var closed = false

    @Synchronized
    fun write(data: ByteArray, offset: Int, count: Int) {
        if (closed) return
        try {
            buffered.write(data, offset, count)
        } catch (e: Exception) {
            fail()
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        try {
            buffered.flush()
            closed = true
            buffered.close()
        } catch (e: Exception) {
            fail()
        }
    }

    private fun fail() {
        closed = true
        runCatching { buffered.close() }
        onFailure()
    }
}
