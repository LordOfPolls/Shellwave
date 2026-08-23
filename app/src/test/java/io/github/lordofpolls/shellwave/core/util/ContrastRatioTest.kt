package io.github.lordofpolls.shellwave.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reference-value checks for [contrastRatio]/[relativeLuminance]. Both numbers are the standard
 * ones cited when cross-checking a from-scratch WCAG contrast implementation: black-on-white is
 * exactly 21:1 (the maximum the formula can ever produce: full luminance range,
 * `(1.0+0.05)/(0.0+0.05)`), and `#767676` on white is the commonly-cited mid-grey that sits right
 * at the WCAG AA normal-text threshold (~4.5:1) - it exists in the spec's own worked examples for
 * exactly this reason. Getting these two numbers right pins the whole formula (linearisation curve,
 * luminance weights, and the `+0.05` offset) before the actual colour-scheme audit below trusts it.
 */
class ContrastRatioTest {

    @Test
    fun `black on white is the maximum possible ratio, 21 point 0 to 1`() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun `white on black equals black on white`() {
        assertEquals(21.0, contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
    }

    @Test
    fun `same colour on itself is 1 to 1`() {
        assertEquals(1.0, contrastRatio(0xFF3C6E48.toInt(), 0xFF3C6E48.toInt()), 0.001)
    }

    @Test
    fun `mid-grey 767676 on white sits at the WCAG AA normal-text boundary`() {
        val ratio = contrastRatio(0xFF767676.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(4.54, ratio, 0.01)
        assert(ratio >= WCAG_AA_NORMAL_TEXT_RATIO) { "767676-on-white ($ratio) is the textbook just-passing grey; a formula bug that fails it needs fixing, not the reference value" }
    }

    @Test
    fun `relative luminance of pure black is zero`() {
        assertEquals(0.0, relativeLuminance(0xFF000000.toInt()), 0.0001)
    }

    @Test
    fun `relative luminance of pure white is one`() {
        assertEquals(1.0, relativeLuminance(0xFFFFFFFF.toInt()), 0.0001)
    }
}
