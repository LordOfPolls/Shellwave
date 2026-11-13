package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_FULL_WIDTH_TERMINAL = "full_width_terminal"
private const val KEY_HIDE_KEY_BAR_WITH_HARDWARE_KEYBOARD = "hide_key_bar_with_hardware_keyboard"

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

    /**
     * Whether the on-screen key bar gives way to a connected physical keyboard, which has every
     * key on it already and leaves the terminal its rows. On by default, with a switch in settings
     * and an entry in the session menu for a mouse or other HID device that reports itself as a
     * keyboard.
     */
    fun getHideKeyBarWithHardwareKeyboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_KEY_BAR_WITH_HARDWARE_KEYBOARD, true)

    /** Sets whether the key bar is hidden while a physical keyboard is connected. */
    fun setHideKeyBarWithHardwareKeyboard(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_KEY_BAR_WITH_HARDWARE_KEYBOARD, hide).apply()
    }
}
