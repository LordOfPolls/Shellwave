package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private const val PREFS_NAME = "shellwave_prefs"

internal fun sharedPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_DYNAMIC_COLOR = "dynamic_color"
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

    fun getDynamicColor(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DYNAMIC_COLOR, true)

    fun setDynamicColor(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
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
