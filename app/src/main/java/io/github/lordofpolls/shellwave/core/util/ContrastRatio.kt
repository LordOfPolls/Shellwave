package io.github.lordofpolls.shellwave.core.util

import kotlin.math.pow

/**
 * Packed-ARGB `Int` in, `Double` out, with no Android or Compose colour type in the signature, so
 * the scheme audit runs as a plain JVM test with no Robolectric and no device.
 *
 * The W3C formula verbatim in place of a third-party contrast-checker library, which makes the
 * reference-value tests (21:1 for black-on-white, ~4.5:1 at the `#767676`-on-white boundary grey)
 * meaningful as a correctness check.
 */
const val WCAG_AA_NORMAL_TEXT_RATIO: Double = 4.5

/** The spec's piecewise definition: a gamma curve with a small linear segment near black. */
private fun linearizeSrgbChannel(channel: Int): Double {
    val proportion = channel / 255.0
    return if (proportion <= 0.03928) proportion / 12.92 else ((proportion + 0.055) / 1.055).pow(2.4)
}

/** Alpha is ignored; every colour fed in here is opaque by construction. */
fun relativeLuminance(color: Int): Double {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return 0.2126 * linearizeSrgbChannel(r) + 0.7152 * linearizeSrgbChannel(g) + 0.0722 * linearizeSrgbChannel(
        b
    )
}

/** In `1.0..21.0`. Argument order doesn't matter; the lighter ends up as the numerator. */
fun contrastRatio(a: Int, b: Int): Double {
    val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
    val darker = minOf(relativeLuminance(a), relativeLuminance(b))
    return (lighter + 0.05) / (darker + 0.05)
}
