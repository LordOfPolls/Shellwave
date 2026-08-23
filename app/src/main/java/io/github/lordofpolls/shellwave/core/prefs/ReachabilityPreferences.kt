package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityPreferences.allowsMetered
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityPreferences.isEnabled

/**
 * A fixed set and not a free-text field: every value here is a promise about how often this app
 * touches the network, and a promise is easier to keep when it comes from a short list.
 */
enum class ReachabilityInterval(val millis: Long, val label: String) {
    THIRTY_SECONDS(30_000, "Every 30 seconds"),
    ONE_MINUTE(60_000, "Every minute"),
    FIVE_MINUTES(300_000, "Every 5 minutes"),
    FIFTEEN_MINUTES(900_000, "Every 15 minutes"),
}

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_REACHABILITY_ENABLED = "reachability_enabled"
private const val KEY_REACHABILITY_INTERVAL = "reachability_interval"
private const val KEY_REACHABILITY_METERED = "reachability_metered"

/**
 * Every default here is the quiet one. This app produces no network traffic beyond the SSH
 * connections the user initiates, and a background poller is the most obvious way to break that. So
 * the feature is off until asked for ([isEnabled] defaults false), and once on it stays off metered
 * networks unless asked again ([allowsMetered] defaults false). A user who never opens Settings
 * never emits a packet they did not ask for.
 */
object ReachabilityPreferences {
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REACHABILITY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REACHABILITY_ENABLED, enabled).apply()
    }

    fun interval(context: Context): ReachabilityInterval {
        val stored = prefs(context).getString(KEY_REACHABILITY_INTERVAL, null)
        return stored?.let { runCatching { ReachabilityInterval.valueOf(it) }.getOrNull() }
            ?: ReachabilityInterval.ONE_MINUTE
    }

    fun setInterval(context: Context, interval: ReachabilityInterval) {
        prefs(context).edit().putString(KEY_REACHABILITY_INTERVAL, interval.name).apply()
    }

    /** Whether probing may continue on a metered connection. Defaults to false - see this object's doc. */
    fun allowsMetered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REACHABILITY_METERED, false)

    fun setAllowsMetered(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_REACHABILITY_METERED, allowed).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
