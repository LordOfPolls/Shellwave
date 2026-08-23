package io.github.lordofpolls.shellwave.feature.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatByteCount] is the only piece of file-transfer logic with no Android or sshj dependency, so
 * it is the one part of that feature a plain JVM test can exercise directly. [FileTransferDialogs]'
 * progress dialog calls this on every `FileTransferController.progress` update while a transfer is
 * running, so a wrong answer here would show up as a wrong number scrolling past on every
 * download/upload, well beyond an edge case - worth pinning down exactly like ShellQuoteTest
 * already does for this project's other pure logic.
 */
class FileTransferControllerTest {

    @Test
    fun `byte counts under 1024 are shown as plain bytes`() {
        assertEquals("0 B", formatByteCount(0))
        assertEquals("1 B", formatByteCount(1))
        assertEquals("1023 B", formatByteCount(1023))
    }

    @Test
    fun `exactly 1024 bytes rolls over to kilobytes`() {
        assertEquals("1.0 KB", formatByteCount(1024))
    }

    @Test
    fun `kilobyte values are rounded to one decimal place`() {
        // 1536 / 1024 = 1.5 exactly
        assertEquals("1.5 KB", formatByteCount(1536))
        // 2000 / 1024 = 1.953125 -> rounds to 2.0
        assertEquals("2.0 KB", formatByteCount(2000))
    }

    @Test
    fun `each unit rolls over to the next at 1024`() {
        assertEquals("1.0 MB", formatByteCount(1024L * 1024))
        assertEquals("1.0 GB", formatByteCount(1024L * 1024 * 1024))
        assertEquals("1.0 TB", formatByteCount(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `values beyond terabytes stay in terabytes`() {
        // units array tops out at TB (formatByteCount's while loop stops at units.lastIndex) - a
        // transfer bigger than that should still print a sane TB figure, not crash or wrap.
        val petabyte = 1024L * 1024 * 1024 * 1024 * 1024
        assertEquals("1024.0 TB", formatByteCount(petabyte))
    }

    @Test
    fun `a realistic mid-transfer byte count formats as megabytes`() {
        assertEquals("4.7 MB", formatByteCount(4_921_000))
    }
}
