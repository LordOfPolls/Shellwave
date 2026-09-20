package io.github.lordofpolls.shellwave.feature.glance

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

// A wake takes no credential, so onClick sends the packet directly - no unlockAndRun needed.
@AndroidEntryPoint
class WolTileService : TileService() {

    @Inject
    lateinit var hostDao: HostDao

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
        val hostId = WidgetPreferences.wolTileHostId(this) ?: return
        scope.launch { wakeHostWithToast(this@WolTileService, hostId) }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val hostId = WidgetPreferences.wolTileHostId(this)
        if (hostId == null) {
            tile.label = "Wake on LAN"
            tile.subtitle = "No host chosen"
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()
            return
        }
        scope.launch {
            val host = hostDao.getById(hostId)
            when {
                host == null -> {
                    tile.label = "Wake on LAN"
                    tile.subtitle = "Host deleted"
                    tile.state = Tile.STATE_UNAVAILABLE
                }

                host.macAddress == null -> {
                    tile.label = host.label ?: host.hostname
                    tile.subtitle = "No MAC address"
                    tile.state = Tile.STATE_UNAVAILABLE
                }

                else -> {
                    tile.label = host.label ?: host.hostname
                    tile.subtitle = null
                    tile.state = Tile.STATE_ACTIVE
                }
            }
            tile.updateTile()
        }
    }
}
