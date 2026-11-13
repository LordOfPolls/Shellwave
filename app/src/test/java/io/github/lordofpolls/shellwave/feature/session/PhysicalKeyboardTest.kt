package io.github.lordofpolls.shellwave.feature.session

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [hasPhysicalKeyboard] is the pure half of the key bar's "hide for a physical keyboard" check. The
 * `InputDevice` sampling around it needs a device, so every case here is built from plain
 * [KeyboardDeviceInfo] values instead.
 */
class PhysicalKeyboardTest {

    private val no = Configuration.HARDKEYBOARDHIDDEN_NO
    private val yes = Configuration.HARDKEYBOARDHIDDEN_YES
    private val undefined = Configuration.HARDKEYBOARDHIDDEN_UNDEFINED

    private val keyboard =
        KeyboardDeviceInfo(
            isVirtual = false,
            isExternal = false,
            isEnabled = true,
            isKeyboard = true,
            isAlphabetic = true,
        )

    private fun check(
        devices: List<KeyboardDeviceInfo>,
        configKeyboardQwerty: Boolean,
        hardKeyboardHidden: Int,
    ) = hasPhysicalKeyboard(devices, configKeyboardQwerty, hardKeyboardHidden)

    @Test
    fun `no devices and no configuration keyboard means none`() {
        assertFalse(check(emptyList(), configKeyboardQwerty = false, hardKeyboardHidden = no))
    }

    @Test
    fun `a qwerty configuration alone counts`() {
        assertTrue(check(emptyList(), configKeyboardQwerty = true, hardKeyboardHidden = no))
    }

    @Test
    fun `an alphabetic keyboard device counts when the configuration says nothing`() {
        assertTrue(check(listOf(keyboard), false, undefined))
    }

    @Test
    fun `a closed lid rules out the configuration and a built-in keyboard`() {
        assertFalse(check(listOf(keyboard), true, yes))
    }

    @Test
    fun `an external keyboard counts even when the configuration reports it hidden`() {
        val external = keyboard.copy(isExternal = true)
        assertTrue(check(listOf(external), false, yes))
    }

    @Test
    fun `a virtual or disabled external keyboard does not override a closed lid`() {
        val devices =
            listOf(
                keyboard.copy(isExternal = true, isVirtual = true),
                keyboard.copy(isExternal = true, isEnabled = false),
            )
        assertFalse(check(devices, false, yes))
    }

    @Test
    fun `virtual, disabled, non-keyboard and non-alphabetic devices do not count`() {
        val others =
            listOf(
                keyboard.copy(isVirtual = true),
                keyboard.copy(isEnabled = false),
                keyboard.copy(isKeyboard = false),
                keyboard.copy(isAlphabetic = false),
            )
        assertFalse(check(others, configKeyboardQwerty = false, hardKeyboardHidden = no))
    }

    @Test
    fun `one qualifying device among others is enough`() {
        val devices =
            listOf(keyboard.copy(isVirtual = true), keyboard.copy(isAlphabetic = false), keyboard)
        assertTrue(check(devices, configKeyboardQwerty = false, hardKeyboardHidden = no))
    }
}
