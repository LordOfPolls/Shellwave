package io.github.lordofpolls.shellwave.ui.design

import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.util.WCAG_AA_NORMAL_TEXT_RATIO
import io.github.lordofpolls.shellwave.core.util.contrastRatio
import io.github.lordofpolls.shellwave.terminal.BUILT_IN_SCHEME_FOREGROUND_BACKGROUND
import io.github.lordofpolls.shellwave.terminal.ansiColors
import io.github.lordofpolls.shellwave.terminal.toHexColorString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-built rather than [BuiltInColorSchemes], for the reason BuiltInColorSchemeContrastTest
 * gives: touching that `object` initializes every property together, and GRUVBOX's cursor reaches
 * `android.graphics.Color` off-device.
 *
 * [foreground]/[background] come from the committed BUILT_IN_SCHEME_FOREGROUND_BACKGROUND map, so
 * the contrast numbers below are the shipped Solarized Dark values. The ANSI slots and
 * cursor/selection are filled with the same two colours - this only needs a structurally valid
 * scheme.
 */
private fun fixtureScheme(name: String, foreground: Int, background: Int): ColorSchemeEntity {
    val ansi = IntArray(16) { index -> if (index % 2 == 0) foreground else background }
    return ColorSchemeEntity(
        id = 0,
        name = name,
        isBuiltIn = true,
        background = background,
        foreground = foreground,
        cursor = foreground,
        selection = background,
        ansiColorsCsv = ansi.joinToString(",") { it.toHexColorString() },
    )
}

/**
 * [SchemeHarmonizer] stays plain-JVM testable despite using the real
 * `com.google.android.material.color.utilities.Blend.harmonize`, which is pure CAM16/HCT maths with
 * no `android.graphics.Color` dependency.
 *
 * How far harmonization can actually degrade contrast: for Solarized Dark, a 729-point accent sweep
 * never crosses AA at all, bottoming out at ~4.70:1 against an unharmonized ~4.75:1. The safety net
 * stays regardless - the test below still finds a degrading accent, and it is cheap.
 */
class SchemeHarmonizerTest {

    // --- harmonizeColor: identity/edge-case behaviour of the real Blend.harmonize ---

    @Test
    fun `pure white is untouched regardless of accent`() {
        assertEquals(
            0xFFFFFFFF.toInt(),
            SchemeHarmonizer.harmonizeColor(0xFFFFFFFF.toInt(), accentArgb = 0xFF3465A4.toInt())
        )
    }

    @Test
    fun `pure black is untouched regardless of accent`() {
        assertEquals(
            0xFF000000.toInt(),
            SchemeHarmonizer.harmonizeColor(0xFF000000.toInt(), accentArgb = 0xFFCC0000.toInt())
        )
    }

    @Test
    fun `harmonizing a colour toward itself is a no-op`() {
        val color = 0xFF839496.toInt()
        assertEquals(color, SchemeHarmonizer.harmonizeColor(color, accentArgb = color))
    }

    @Test
    fun `mid-grey is untouched regardless of accent`() {
        assertEquals(
            0xFF808080.toInt(),
            SchemeHarmonizer.harmonizeColor(0xFF808080.toInt(), accentArgb = 0xFF00C0FF.toInt())
        )
    }

    @Test
    fun `alpha channel survives harmonization as fully opaque`() {
        val result =
            SchemeHarmonizer.harmonizeColor(0xFFCC0000.toInt(), accentArgb = 0xFF268BD2.toInt())
        assertEquals(0xFF, (result ushr 24) and 0xFF)
    }

    @Test
    fun `a saturated colour moves toward the accent hue`() {
        // Solarized Dark's foreground (base0, #839496) toward a warm red/orange accent (#E64C19).
        val result =
            SchemeHarmonizer.harmonizeColor(0xFF839496.toInt(), accentArgb = 0xFFE64C19.toInt())
        assertEquals(0xFF839492.toInt(), result)
    }

    // --- harmonize(): re-measured contrast findings, with real Blend.harmonize numbers ---

    @Test
    fun `Solarized Dark stays above AA against the accent that broke the HSL approximation`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Dark")
        val accent =
            0xFFE64C19.toInt() // the exact accent the earlier HSL pass measured a 4.75 -> 4.28 (AA-crossing) drop with

        val originalRatio = contrastRatio(fg, bg)
        val harmonizedRatio = contrastRatio(
            SchemeHarmonizer.harmonizeColor(fg, accent),
            SchemeHarmonizer.harmonizeColor(bg, accent)
        )

        assertEquals(4.75, originalRatio, 0.01)
        assertEquals(4.72, harmonizedRatio, 0.01)
        assertTrue(
            "Real Blend.harmonize should NOT cross AA here, unlike the earlier HSL approximation",
            harmonizedRatio >= WCAG_AA_NORMAL_TEXT_RATIO
        )
    }

    @Test
    fun `Solarized Light stays a known AA failure, not a new one`() {
        // Solarized Light already fails AA unharmonized at ~4.13:1 and is left that way; see
        // BuiltInColorSchemeContrastTest. The sweep's worst accent for it (#00C0FF) drops it to
        // ~4.11:1 - still a failure, but not one harmonization introduced.
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Light")
        val accent = 0xFF00C0FF.toInt()

        val originalRatio = contrastRatio(fg, bg)
        val harmonizedRatio = contrastRatio(
            SchemeHarmonizer.harmonizeColor(fg, accent),
            SchemeHarmonizer.harmonizeColor(bg, accent)
        )

        assertEquals(4.13, originalRatio, 0.01)
        assertEquals(4.11, harmonizedRatio, 0.01)
        assertTrue(
            "Both original and harmonized should already be known AA failures",
            originalRatio < WCAG_AA_NORMAL_TEXT_RATIO && harmonizedRatio < WCAG_AA_NORMAL_TEXT_RATIO
        )
    }

    @Test
    fun `no accent in a 729-point sweep takes Solarized Dark below AA`() {
        // 9 values per RGB channel = 729 accents, against the tightest-margin passing scheme. Worst
        // case ~4.70:1, degraded from ~4.75:1 but never below the 4.5:1 AA line.
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Dark")
        val steps = listOf(0, 32, 64, 96, 128, 160, 192, 224, 255)
        var worstRatio = Double.MAX_VALUE
        for (r in steps) for (g in steps) for (b in steps) {
            val accent = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            val ratio = contrastRatio(
                SchemeHarmonizer.harmonizeColor(fg, accent),
                SchemeHarmonizer.harmonizeColor(bg, accent)
            )
            if (ratio < worstRatio) worstRatio = ratio
        }
        assertTrue(
            "Worst sampled ratio was $worstRatio - expected it to stay >= AA",
            worstRatio >= WCAG_AA_NORMAL_TEXT_RATIO
        )
        assertTrue(
            "Worst sampled ratio was $worstRatio - expected some measurable degradation from ~4.75:1 to exercise the safety net elsewhere",
            worstRatio < contrastRatio(fg, bg)
        )
    }

    @Test
    fun `harmonize() keeps the original pair when the blend degrades contrast`() {
        // A sweep-found accent that measurably drops Solarized Dark (~4.75:1 -> ~4.70:1) without
        // crossing AA. The safety net's condition is "any degradation", not "only an AA-crossing
        // one", so this is the case that proves it engages.
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Dark")
        val scheme = fixtureScheme("Solarized Dark", fg, bg)
        val accent = 0xFF00C0FF.toInt()

        val harmonized = SchemeHarmonizer.harmonize(scheme, accent)

        assertEquals(
            "fg must revert to the original when the blend loses contrast",
            fg,
            harmonized.foreground
        )
        assertEquals(
            "bg must revert to the original when the blend loses contrast",
            bg,
            harmonized.background
        )
        assertEquals(
            contrastRatio(fg, bg),
            contrastRatio(harmonized.foreground, harmonized.background),
            0.001
        )

        // The cursor (== foreground in this fixture) still harmonizes, proving this is not a
        // blanket "give up on the whole scheme" fallback.
        assertNotEquals(
            "cursor should still harmonize even though fg/bg reverted",
            fg,
            harmonized.cursor
        )
    }

    @Test
    fun `harmonize() lets a contrast-safe blend through`() {
        // A small same-family nudge that slightly improves an already-huge (~13.4:1) pair, so the
        // safety net should be a no-op while harmonization still visibly ran.
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Dracula")
        val scheme = fixtureScheme("Dracula", fg, bg)
        val accent = 0xFFBD93F9.toInt()

        val harmonized = SchemeHarmonizer.harmonize(scheme, accent)

        assertTrue(
            contrastRatio(harmonized.foreground, harmonized.background) >= contrastRatio(
                fg,
                bg
            ) - 0.01
        )
    }

    @Test
    fun `harmonize() preserves the 16-slot ANSI CSV shape`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Nord")
        val scheme = fixtureScheme("Nord", fg, bg)

        val harmonized = SchemeHarmonizer.harmonize(scheme, accentArgb = 0xFF88C0D0.toInt())

        assertEquals(16, harmonized.ansiColors().size)
    }

    // --- resolve(): the "Exact scheme colours" escape hatch ---

    @Test
    fun `resolve() with exactSchemeColours true returns the scheme untouched`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Light")
        val scheme = fixtureScheme("Solarized Light", fg, bg)
        val accent = 0xFF6650A4.toInt()

        val resolved = SchemeHarmonizer.resolve(scheme, accent, exactSchemeColours = true)

        assertEquals(
            "exact mode must be a true identity, not merely a zero-degree harmonize",
            scheme,
            resolved
        )
    }

    @Test
    fun `resolve() with exactSchemeColours false is equivalent to harmonize()`() {
        val (fg, bg) = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Gruvbox")
        val scheme = fixtureScheme("Gruvbox", fg, bg)
        val accent = 0xFF4285F4.toInt()

        assertEquals(
            SchemeHarmonizer.harmonize(scheme, accent),
            SchemeHarmonizer.resolve(scheme, accent, exactSchemeColours = false)
        )
    }
}
