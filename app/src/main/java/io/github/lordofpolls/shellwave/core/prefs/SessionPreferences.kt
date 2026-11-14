package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_CLOSE_ON_EXIT = "close_session_on_exit"

/**
 * What happens to a tab when its shell ends cleanly (`exit`, `logout`, the server closing the
 * session). Off keeps the tab as DISCONNECTED, so the output stays readable and Reconnect is offered;
 * on closes the session and drops that output.
 */
object SessionPreferences {
    fun getCloseOnExit(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLOSE_ON_EXIT, false)

    fun setCloseOnExit(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLOSE_ON_EXIT, enabled).apply()
    }
}
