package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far a screen indents its scrolling content on a wide window, so it stays a readable column.
 * Applied inside each scroll container rather than as a cap around the screen: a cap left the side
 * margins dead to a swipe and cut the top bar's scroll tint off at the column's edges.
 */
val LocalReadableInset = compositionLocalOf { 0.dp }

fun readableInset(availableWidth: Dp): Dp = ((availableWidth - 640.dp) / 2).coerceAtLeast(0.dp)
