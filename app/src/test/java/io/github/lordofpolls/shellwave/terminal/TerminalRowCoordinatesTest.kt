package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalRowCoordinatesTest {

    private class NoopOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private class NoopClient : TerminalSessionClient {
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
    }

    private fun emulator(transcriptRows: Int = 100): TerminalEmulator =
        TerminalEmulator(NoopOutput(), 20, 4, 0, 0, transcriptRows, NoopClient())

    @Test
    fun `collectTerminalRows spans from the top of scrollback to the bottom of the live screen`() {
        val emulator = emulator()
        emulator.append("first\r\nsecond\r\nthird\r\nfourth\r\n".toByteArray(), 30)

        val rows = collectTerminalRows(emulator)

        assertEquals(emulator.screen.activeTranscriptRows + emulator.mRows, rows.size)
        assertEquals("first", rows.first().trimEnd())
    }

    @Test
    fun `externalRowAt converts a collectTerminalRows index back to topRow's coordinate system`() {
        val emulator = emulator()
        emulator.append("first\r\nsecond\r\nthird\r\nfourth\r\n".toByteArray(), 30)

        val transcriptRows = emulator.screen.activeTranscriptRows
        assertEquals(-transcriptRows, externalRowAt(emulator, 0))
        assertEquals(0, externalRowAt(emulator, transcriptRows))
    }
}
