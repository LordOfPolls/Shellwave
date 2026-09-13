package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ColourTheme { DYNAMIC, SCHEMATIC, NOTHING }

private const val PREFS_NAME = "shellwave_prefs"

internal fun sharedPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_DYNAMIC_COLOR = "dynamic_color"
private const val KEY_COLOUR_THEME = "colour_theme"
private const val KEY_EXACT_SCHEME_COLOURS = "exact_scheme_colours"

/** Shares [PREFS_NAME] with [BellPreferences]/[SessionLayoutPreferences]. */
object AppearancePreferences {
    fun getThemeMode(context: Context): ThemeMode {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, null)
        return stored?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getColourTheme(context: Context): ColourTheme {
        val prefs = sharedPrefs(context)
        val stored = prefs.getString(KEY_COLOUR_THEME, null)
        return stored?.let { runCatching { ColourTheme.valueOf(it) }.getOrNull() }
            // Pre-1.8 devices only ever wrote the boolean; honour it until they pick explicitly.
            ?: if (prefs.getBoolean(KEY_DYNAMIC_COLOR, true)) ColourTheme.DYNAMIC else ColourTheme.SCHEMATIC
    }

    fun setColourTheme(context: Context, theme: ColourTheme) {
        sharedPrefs(context).edit().putString(KEY_COLOUR_THEME, theme.name).apply()
    }

    /** `true` means exact/unharmonized. */
    fun getExactSchemeColours(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXACT_SCHEME_COLOURS, false)

    fun setExactSchemeColours(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_EXACT_SCHEME_COLOURS, enabled).apply()
    }
}
