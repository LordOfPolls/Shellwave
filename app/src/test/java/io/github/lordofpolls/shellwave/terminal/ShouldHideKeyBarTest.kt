package io.github.lordofpolls.shellwave.terminal

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldHideKeyBarTest {
    private val qwerty = Configuration.KEYBOARD_QWERTY
    private val noKeys = Configuration.KEYBOARD_NOKEYS
    private val exposed = Configuration.HARDKEYBOARDHIDDEN_NO
    private val covered = Configuration.HARDKEYBOARDHIDDEN_YES
    private val unknown = Configuration.HARDKEYBOARDHIDDEN_UNDEFINED

    private val builtIn = KeyboardDeviceInfo(
        isVirtual = false,
        isExternal = false,
        isEnabled = true,
        isKeyboard = true,
        isAlphabetic = true,
    )
    private val external = builtIn.copy(isExternal = true)

    @Test
    fun `configuration alone hides only when asked to and a keyboard is usable`() {
        assertTrue(shouldHideKeyBar(true, qwerty, exposed, emptyList()))
        assertFalse(shouldHideKeyBar(false, qwerty, exposed, emptyList()))
        assertFalse(shouldHideKeyBar(true, noKeys, exposed, emptyList()))
        assertFalse(shouldHideKeyBar(true, qwerty, covered, emptyList()))
        assertFalse(shouldHideKeyBar(true, qwerty, unknown, emptyList()))
    }

    @Test
    fun `a keyboard device counts when the configuration says nothing`() {
        assertTrue(shouldHideKeyBar(true, noKeys, unknown, listOf(builtIn)))
        assertFalse(shouldHideKeyBar(false, noKeys, unknown, listOf(external)))
    }

    @Test
    fun `an external keyboard ignores the hidden flag but a built-in one does not`() {
        assertTrue(shouldHideKeyBar(true, noKeys, covered, listOf(external)))
        assertFalse(shouldHideKeyBar(true, qwerty, covered, listOf(builtIn)))
    }

    @Test
    fun `virtual, disabled, non-keyboard and non-alphabetic devices do not count`() {
        val others = listOf(
            external.copy(isVirtual = true),
            external.copy(isEnabled = false),
            external.copy(isKeyboard = false),
            external.copy(isAlphabetic = false),
        )
        assertFalse(shouldHideKeyBar(true, noKeys, exposed, others))
    }
}
