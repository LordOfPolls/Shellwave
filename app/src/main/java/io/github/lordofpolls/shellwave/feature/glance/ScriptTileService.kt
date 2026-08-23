package io.github.lordofpolls.shellwave.feature.glance

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.github.lordofpolls.shellwave.core.db.dao.ScriptDao
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import io.github.lordofpolls.shellwave.service.ScriptTriggerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

// A QS tile can be tapped from the lock screen panel, hence unlockAndRun around the trigger.
@AndroidEntryPoint
class ScriptTileService : TileService() {

    @Inject
    lateinit var scriptDao: ScriptDao

    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val scriptId = WidgetPreferences.qsTileScriptId(this) ?: return
        unlockAndRun {
            ScriptTriggerService.start(this, scriptId)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val scriptId = WidgetPreferences.qsTileScriptId(this)
        if (scriptId == null) {
            tile.label = "Shellwave"
            tile.subtitle = "No script chosen"
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()
            return
        }
        scope.launch {
            val script = scriptDao.getById(scriptId)
            tile.label = script?.name ?: "Shellwave"
            tile.subtitle = if (script == null) "Script deleted" else null
            tile.state = if (script != null) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
            tile.updateTile()
        }
    }
}
