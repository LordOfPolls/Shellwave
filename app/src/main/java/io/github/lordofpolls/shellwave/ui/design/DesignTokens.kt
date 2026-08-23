package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.R

/**
 * The chrome's mono face, independent of the user's chosen terminal font. Same bundled
 * `R.font.jetbrains_mono` asset as the terminal, but as a Compose `FontFamily`: the chrome is the
 * app speaking, the grid is the server speaking, and only the latter is themeable.
 */
val ChromeMonoFontFamily = FontFamily(Font(R.font.jetbrains_mono))

/** Fixed here so no call site scales it. */
val StatusSquareSize: Dp = 8.dp

/**
 * Status colours never come from the dynamic accent: accent means identity and navigation, status
 * means condition, and a FAILED session's red should not drift with the wallpaper. `crit` reuses
 * M3's baseline `error`/`errorContainer(dark)` hex, pinned here as our own constant.
 *
 * Light and dark variants exist because a status square sits on whatever surface is around it and
 * one mid-tone hue can't stay comfortable against both. Every pair clears 5:1.
 */
object StatusColors {
    val okLight = Color(0xFF2E7D32)
    val okDark = Color(0xFF81C995)
    val warnLight = Color(0xFF8F5F00)
    val warnDark = Color(0xFFFFCB6B)
    val critLight = Color(0xFFBA1A1A)
    val critDark = Color(0xFFFFB4AB)
    val neutralLight = Color(0xFF49454F)
    val neutralDark = Color(0xFFCAC4D0)
}

@Composable
@ReadOnlyComposable
fun StatusColors.ok(): Color = if (LocalShellwaveDarkTheme.current) okDark else okLight

@Composable
@ReadOnlyComposable
fun StatusColors.warn(): Color = if (LocalShellwaveDarkTheme.current) warnDark else warnLight

@Composable
@ReadOnlyComposable
fun StatusColors.crit(): Color = if (LocalShellwaveDarkTheme.current) critDark else critLight

@Composable
@ReadOnlyComposable
fun StatusColors.neutral(): Color =
    if (LocalShellwaveDarkTheme.current) neutralDark else neutralLight
