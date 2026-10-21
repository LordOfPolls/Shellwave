package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

enum class HostSort(val label: String) {
    ALPHABETICAL("Alphabetical"),
    RECENT("Recently used"),
    MOST_USED("Most used"),
    MANUAL("Manual"),
}

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_HOST_SORT = "host_sort"

object HostListPreferences {
    fun sort(context: Context): HostSort {
        val stored = prefs(context).getString(KEY_HOST_SORT, null)
        return stored?.let { runCatching { HostSort.valueOf(it) }.getOrNull() }
            ?: HostSort.ALPHABETICAL
    }

    fun setSort(context: Context, sort: HostSort) {
        prefs(context).edit { putString(KEY_HOST_SORT, sort.name) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
