package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * "Schematic", used whenever [dynamicColor] is off. Never a fallback for older devices: minSdk 31
 * guarantees dynamic colour exists. Only `primary` is fixed to the brand hex; everything else is
 * Material 3's own baseline.
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
 * Threaded down rather than re-derived, because `isSystemInDarkTheme()` alone is wrong here: the
 * theme-mode setting can force light or dark independent of the system. [StatusColors] needs this
 * one bit of theme state directly, being fixed constants that must stay independent of
 * [MaterialTheme.colorScheme].
 */
val LocalShellwaveDarkTheme = staticCompositionLocalOf { false }

@Composable
fun ShellwaveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> SchematicDarkColors
        else -> SchematicLightColors
    }

    CompositionLocalProvider(LocalShellwaveDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
