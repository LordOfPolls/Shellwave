package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_FULL_WIDTH_TERMINAL = "full_width_terminal"
private const val KEY_HIDE_KEY_BAR_WITH_KEYBOARD = "hide_key_bar_with_keyboard"

/**
 * Whether the terminal takes the whole wide/unfolded window. Two panes are a choice, never a tax:
 * `tmux` splits, `htop` and long log lines all want every column the unfolded screen has.
 */
object SessionLayoutPreferences {
    fun getFullWidthTerminal(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FULL_WIDTH_TERMINAL, false)

    fun setFullWidthTerminal(context: Context, fullWidth: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FULL_WIDTH_TERMINAL, fullWidth).apply()
    }

    fun getHideKeyBarWithKeyboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_KEY_BAR_WITH_KEYBOARD, false)

    fun setHideKeyBarWithKeyboard(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_HIDE_KEY_BAR_WITH_KEYBOARD, hide) }
    }
}
