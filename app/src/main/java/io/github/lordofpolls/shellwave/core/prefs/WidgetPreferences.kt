package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_PINNED_SCRIPT_IDS = "widget_pinned_script_ids"
private const val KEY_QS_TILE_SCRIPT_ID = "qs_tile_script_id"

object WidgetPreferences {
    fun pinnedScriptIds(context: Context): Set<Long> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED_SCRIPT_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun isPinned(context: Context, scriptId: Long): Boolean = scriptId in pinnedScriptIds(context)

    fun setPinned(context: Context, scriptId: Long, pinned: Boolean) {
        val current = pinnedScriptIds(context).toMutableSet()
        if (pinned) current += scriptId else current -= scriptId
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PINNED_SCRIPT_IDS, current.map { it.toString() }.toSet())
            .apply()
    }

    fun qsTileScriptId(context: Context): Long? {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_QS_TILE_SCRIPT_ID, -1L)
        return id.takeIf { it >= 0 }
    }

    fun setQsTileScriptId(context: Context, scriptId: Long?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (scriptId == null) editor.remove(KEY_QS_TILE_SCRIPT_ID) else editor.putLong(
            KEY_QS_TILE_SCRIPT_ID,
            scriptId
        )
        editor.apply()
    }
}
