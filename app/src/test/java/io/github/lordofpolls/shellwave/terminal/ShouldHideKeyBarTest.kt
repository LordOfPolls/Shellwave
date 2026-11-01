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

    @Test
    fun `hides only when asked to and a keyboard is usable`() {
        assertTrue(shouldHideKeyBar(true, qwerty, exposed))
        assertFalse(shouldHideKeyBar(false, qwerty, exposed))
        assertFalse(shouldHideKeyBar(true, noKeys, exposed))
        assertFalse(shouldHideKeyBar(true, qwerty, covered))
        assertFalse(shouldHideKeyBar(true, qwerty, unknown))
    }
}
