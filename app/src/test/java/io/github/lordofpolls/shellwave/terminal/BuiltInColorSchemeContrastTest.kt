package io.github.lordofpolls.shellwave.terminal

import io.github.lordofpolls.shellwave.core.util.WCAG_AA_NORMAL_TEXT_RATIO
import io.github.lordofpolls.shellwave.core.util.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every [BuiltInColorSchemes] entry's foreground/background pair against WCAG AA for normal text,
 * using the from-scratch formula in contrastRatio.
 *
 * It reads [BUILT_IN_SCHEME_FOREGROUND_BACKGROUND] rather than BuiltInColorSchemes itself, which a
 * plain JVM test cannot touch: Kotlin initialises every property of an `object` together, and
 * `GRUVBOX`'s cursor reaches the vendored engine's `getPerceivedBrightnessOfColor`, which calls
 * `android.graphics.Color`. That map is Android-free and is also what [BuiltInColorSchemes] builds
 * its own values from, so there is one committed value per scheme and the audit cannot drift from
 * the source.
 *
 * Solarized Light fails at ~4.13:1 and stays that way: a built-in is a faithful reproduction of a
 * named palette, and darkening base01-on-base3 to reach 4.5:1 would make it stop being Solarized.
 * The assertion pins a narrow band instead of a floor, so an accidental edit fails either way and a
 * real fix has to update this test with it. Each scheme is asserted individually so a regression
 * names the scheme in the output.
 */
class BuiltInColorSchemeContrastTest {

    @Test
    fun `Solarized Dark passes AA (just) at about 4 point 75 to 1`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Dark")
        val ratio = contrastRatio(fg, bg)
        assertEquals(4.75, ratio, 0.05)
        assertTrue("Solarized Dark should pass AA", ratio >= WCAG_AA_NORMAL_TEXT_RATIO)
    }

    @Test
    fun `Solarized Light fails AA at about 4 point 13 to 1`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Light")
        val ratio = contrastRatio(fg, bg)
        assertEquals(4.13, ratio, 0.05)
        assertTrue(
            "Solarized Light is known to fail AA (~4.13:1) - this test documents that, it doesn't ask for a fix",
            ratio < WCAG_AA_NORMAL_TEXT_RATIO
        )
    }

    @Test
    fun `Gruvbox passes AA comfortably`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Gruvbox")
        val ratio = contrastRatio(fg, bg)
        assertTrue("Gruvbox ratio was $ratio", ratio >= WCAG_AA_NORMAL_TEXT_RATIO)
    }

    @Test
    fun `Nord passes AA comfortably`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Nord")
        val ratio = contrastRatio(fg, bg)
        assertTrue("Nord ratio was $ratio", ratio >= WCAG_AA_NORMAL_TEXT_RATIO)
    }

    @Test
    fun `Dracula passes AA comfortably`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Dracula")
        val ratio = contrastRatio(fg, bg)
        assertTrue("Dracula ratio was $ratio", ratio >= WCAG_AA_NORMAL_TEXT_RATIO)
    }

    @Test
    fun `Tango passes AA at the maximum ratio`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Tango")
        val ratio = contrastRatio(fg, bg)
        assertEquals(21.0, ratio, 0.01)
    }

    @Test
    fun `Campbell passes AA comfortably`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Campbell")
        val ratio = contrastRatio(fg, bg)
        assertTrue("Campbell ratio was $ratio", ratio >= WCAG_AA_NORMAL_TEXT_RATIO)
    }

    /**
     * Unlike the built-ins above, [DEFAULT_COLOR_SCHEME] is safe to reference directly: it's a plain
     * top-level `val` outside the `BuiltInColorSchemes` object, and its initializer only copies
     * [com.termux.terminal.TerminalColorScheme]'s static `mDefaultColors` array (no
     * `android.graphics.Color` call in that path).
     */
    @Test
    fun `DEFAULT_COLOR_SCHEME passes AA at the maximum ratio`() {
        val ratio = contrastRatio(DEFAULT_COLOR_SCHEME.foreground, DEFAULT_COLOR_SCHEME.background)
        assertEquals(21.0, ratio, 0.01)
    }
}
