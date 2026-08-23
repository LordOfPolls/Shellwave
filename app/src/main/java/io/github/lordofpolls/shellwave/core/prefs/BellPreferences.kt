package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences.label

enum class BellMode { SILENT, VIBRATE, NOTIFY }

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_BELL_MODE = "bell_mode"

/**
 * SharedPreferences over DataStore: `SshConnection.onBell()` is a plain `TerminalOutput` override,
 * so it needs a synchronous read. A DataStore `Flow` would want an always-collecting coroutine to
 * fake one.
 */
object BellPreferences {
    fun get(context: Context): BellMode {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BELL_MODE, null)
        return stored?.let { runCatching { BellMode.valueOf(it) }.getOrNull() } ?: BellMode.VIBRATE
    }

    fun set(context: Context, mode: BellMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_BELL_MODE, mode.name).apply()
    }

    /**
     * Just the value, no "Bell:" prefix. Separate from [label] because building the parent menu item's
     * text by prefixing [label] gave "Bell: Bell: Vibrate" on device.
     */
    fun modeName(mode: BellMode): String =
        when (mode) {
            BellMode.SILENT -> "Silent"
            BellMode.VIBRATE -> "Vibrate"
            BellMode.NOTIFY -> "Notify"
        }

    fun label(mode: BellMode): String = "Bell: ${modeName(mode)}"
}
