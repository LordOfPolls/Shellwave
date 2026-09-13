package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.lordofpolls.shellwave.core.prefs.ColourTheme

/**
 * "Schematic", used whenever [ColourTheme.SCHEMATIC] is selected. Never a fallback for older
 * devices: minSdk 31 guarantees dynamic colour exists. Only `primary` is fixed to the brand hex;
 * everything else is Material 3's own baseline.
 */
private val SchematicLightColors = lightColorScheme(
    primary = Color(0xFF4D7C0F),
    secondary = Color(0xFF53634F),
    tertiary = Color(0xFF396569),
)

private val SchematicDarkColors = darkColorScheme(
    primary = Color(0xFFA3E635),
    secondary = Color(0xFFBBCCB5),
    tertiary = Color(0xFFA1CDD1),
)

/**
 * Nothing design tokens. Monochrome surfaces and text; the signal red is `primary` so it lands
 * only on live and selected things (FAB, switches, focus rings, the wordmark tilde) while
 * `secondaryContainer` stays grey so the nav pill and tonal buttons don't compete with it.
 */
private val NothingDarkColors = darkColorScheme(
    primary = Color(0xFFD71921),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD71921),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF8A0F15),
    secondary = Color(0xFF999999),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary = Color(0xFFD71921),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF2B0608),
    onTertiaryContainer = Color(0xFFFFB4AB),
    error = Color(0xFFD71921),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF2B0608),
    onErrorContainer = Color(0xFFFFB4AB),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF999999),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF000000),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF222222),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF222222),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
)

private val NothingLightColors = lightColorScheme(
    primary = Color(0xFFD71921),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD71921),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFFFB4AB),
    secondary = Color(0xFF666666),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFFD71921),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBDCDD),
    onTertiaryContainer = Color(0xFF8A0F15),
    error = Color(0xFFD71921),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDCDD),
    onErrorContainer = Color(0xFF8A0F15),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF666666),
    surfaceTint = Color(0xFF000000),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFF5F5F5),
    outline = Color(0xFFCCCCCC),
    outlineVariant = Color(0xFFE8E8E8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8E8E8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceContainerHighest = Color(0xFFE8E8E8),
)

/**
 * Dot-matrix only at title size and up: Doto is unreadable as body copy and Nothing itself keeps
 * NDot for clocks, titles and hero numbers.
 */
private val NothingTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = NothingDisplayFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = NothingDisplayFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = NothingDisplayFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = NothingDisplayFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = NothingDisplayFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = NothingDisplayFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = NothingDisplayFontFamily),
    )
}

/**
 * Threaded down rather than re-derived, because `isSystemInDarkTheme()` alone is wrong here: the
 * theme-mode setting can force light or dark independent of the system. [StatusColors] needs this
 * one bit of theme state directly, being fixed constants that must stay independent of
 * [MaterialTheme.colorScheme].
 */
val LocalShellwaveDarkTheme = staticCompositionLocalOf { false }

@Composable
fun ShellwaveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colourTheme: ColourTheme = ColourTheme.DYNAMIC,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (colourTheme) {
        ColourTheme.DYNAMIC -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        ColourTheme.SCHEMATIC -> if (darkTheme) SchematicDarkColors else SchematicLightColors
        ColourTheme.NOTHING -> if (darkTheme) NothingDarkColors else NothingLightColors
    }

    CompositionLocalProvider(LocalShellwaveDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (colourTheme == ColourTheme.NOTHING) NothingTypography else Typography(),
            content = content,
        )
    }
}
