package io.github.lordofpolls.shellwave.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardShortcutTest {
    private fun shortcut(
        keyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ) = clipboardShortcut(keyCode, ctrl, shift, alt)

    @Test
    fun `plain Ctrl+V stays a control byte for the remote`() {
        assertNull(shortcut(KeyEvent.KEYCODE_V, ctrl = true))
    }

    @Test
    fun `Ctrl+Shift+V pastes`() {
        assertEquals(
            ClipboardShortcut.PASTE,
            shortcut(KeyEvent.KEYCODE_V, ctrl = true, shift = true),
        )
    }

    @Test
    fun `Ctrl+Shift+C copies`() {
        assertEquals(
            ClipboardShortcut.COPY,
            shortcut(KeyEvent.KEYCODE_C, ctrl = true, shift = true),
        )
    }

    @Test
    fun `Shift+Insert pastes`() {
        assertEquals(ClipboardShortcut.PASTE, shortcut(KeyEvent.KEYCODE_INSERT, shift = true))
    }

    @Test
    fun `Ctrl+Insert is not a shortcut`() {
        assertNull(shortcut(KeyEvent.KEYCODE_INSERT, ctrl = true))
    }

    @Test
    fun `Alt+Shift+V is left to the Alt path`() {
        assertNull(shortcut(KeyEvent.KEYCODE_V, shift = true, alt = true))
    }
}
