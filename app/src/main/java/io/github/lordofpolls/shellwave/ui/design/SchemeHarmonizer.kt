package io.github.lordofpolls.shellwave.ui.design

import android.annotation.SuppressLint
import com.google.android.material.color.utilities.Blend
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.util.contrastRatio
import io.github.lordofpolls.shellwave.terminal.ansiColors
import io.github.lordofpolls.shellwave.terminal.toHexColorString

/**
 * Nudges a terminal scheme toward the Material You accent so it does not clash with dynamically
 * themed chrome. `Blend.harmonize` keeps that subtle by itself: CAM16 hue moves at most half the
 * angular distance, capped at 15 degrees, and chroma and tone are left alone, so harmonized
 * Solarized still reads as Solarized.
 *
 * The real `Blend` costs a dependency on `com.google.android.material`, a View-system library in an
 * otherwise-Compose app, for one function that sits behind `@RestrictTo(LIBRARY_GROUP)`. Still
 * better than approximating CAM16/HCT with an HSL hue rotation.
 *
 * Plain ARGB `Int`s in and out, so this is JVM-testable and safe to call from a preview.
 */
object SchemeHarmonizer {

    // If Material ever moves or renames Blend, fix the build break; don't vendor Google's source to
    // route around it.
    @SuppressLint("RestrictedApi")
    fun harmonizeColor(colorArgb: Int, accentArgb: Int): Int =
        Blend.harmonize(colorArgb, accentArgb)

    /**
     * [ColorSchemeEntity.selection] is excluded: it is a translucent overlay this app draws itself.
     *
     * The contrast check is not hypothetical. Harmonizing Solarized Dark toward a warm accent
     * measurably degrades its foreground/background pair, so that pair reverts while the ANSI colours
     * and cursor still harmonize.
     */
    fun harmonize(scheme: ColorSchemeEntity, accentArgb: Int): ColorSchemeEntity {
        val harmonizedAnsi = scheme.ansiColors().map { harmonizeColor(it, accentArgb) }.toIntArray()
        val harmonizedForeground = harmonizeColor(scheme.foreground, accentArgb)
        val harmonizedBackground = harmonizeColor(scheme.background, accentArgb)
        val harmonizedCursor = harmonizeColor(scheme.cursor, accentArgb)

        val originalContrast = contrastRatio(scheme.foreground, scheme.background)
        val harmonizedContrast = contrastRatio(harmonizedForeground, harmonizedBackground)
        val (finalForeground, finalBackground) =
            if (harmonizedContrast < originalContrast) {
                scheme.foreground to scheme.background
            } else {
                harmonizedForeground to harmonizedBackground
            }

        return scheme.copy(
            foreground = finalForeground,
            background = finalBackground,
            cursor = harmonizedCursor,
            ansiColorsCsv = harmonizedAnsi.joinToString(",") { it.toHexColorString() },
        )
    }

    fun resolve(
        scheme: ColorSchemeEntity,
        accentArgb: Int,
        exactSchemeColours: Boolean
    ): ColorSchemeEntity =
        if (exactSchemeColours) scheme else harmonize(scheme, accentArgb)
}
