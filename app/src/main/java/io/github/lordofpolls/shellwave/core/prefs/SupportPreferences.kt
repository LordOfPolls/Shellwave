package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_USE_COUNT = "support_use_count"
private const val KEY_PROMPT_SETTLED = "support_prompt_settled"

private const val PROMPT_AT_USE = 20

/**
 * A local tally of connections and script runs, so the donation prompt can be offered
 */
object SupportPreferences {
    fun recordUse(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_PROMPT_SETTLED, false)) return
        prefs.edit().putInt(KEY_USE_COUNT, prefs.getInt(KEY_USE_COUNT, 0) + 1).apply()
    }

    fun shouldPrompt(context: Context): Boolean {
        val prefs = prefs(context)
        return !prefs.getBoolean(KEY_PROMPT_SETTLED, false) &&
            prefs.getInt(KEY_USE_COUNT, 0) >= PROMPT_AT_USE
    }

    fun markPromptSettled(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPT_SETTLED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
