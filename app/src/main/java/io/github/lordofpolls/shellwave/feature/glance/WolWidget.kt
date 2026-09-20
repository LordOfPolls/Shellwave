package io.github.lordofpolls.shellwave.feature.glance

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.android.EntryPointAccessors
import io.github.lordofpolls.shellwave.MainActivity
import io.github.lordofpolls.shellwave.core.net.sendMagicPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Shared by [WolTileService.onClick] and [WakeHostAction] - the only two triggers for a wake.
 * Mirrors the toast shape `MainActivity`'s own Wake menu item uses.
 *
 * On API 36+ `ACCESS_LOCAL_NETWORK` is granted from MainActivity, so a tile or widget tap on an
 * install that has never been opened fails here; the toast carries the exception message.
 */
internal suspend fun wakeHostWithToast(context: Context, hostId: Long) {
    val hostDao =
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).hostDao()
    val host = hostDao.getById(hostId)
    val mac = host?.macAddress
    val message = when {
        host == null -> "That host no longer exists."
        // The widget row can outlive the MAC being cleared; "" is not a MAC address helps nobody.
        mac == null -> "${host.label ?: host.hostname} has no MAC address."
        else -> runCatching { sendMagicPacket(mac) }
            .fold(
                { "Wake packet sent to ${host.label ?: host.hostname}" },
                { it.message ?: "Couldn't send the wake packet" },
            )
    }
    withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

class WakeHostAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val hostId = parameters[HOST_ID_KEY] ?: return
        wakeHostWithToast(context, hostId)
    }

    companion object {
        val HOST_ID_KEY = ActionParameters.Key<Long>("hostId")
    }
}

class WolWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val hostDao =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).hostDao()
        val wolHosts = hostDao.observeAll().map { hosts -> hosts.filter { it.macAddress != null } }
        val initialHosts = wolHosts.first()

        provideContent {
            val hosts by wolHosts.collectAsState(initialHosts)
            Column(
                modifier = GlanceModifier.fillMaxSize()
                    .background(GlanceTheme.colors.widgetBackground)
                    .padding(12.dp),
            ) {
                Text(
                    "Wake on LAN",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    modifier = GlanceModifier.fillMaxWidth()
                        .clickable(actionStartActivity<MainActivity>()),
                )
                if (hosts.isEmpty()) {
                    Text(
                        "No host has a MAC address - add one when editing a host.",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp
                        ),
                        modifier = GlanceModifier.padding(top = 8.dp),
                    )
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(hosts, itemId = { it.id }) { host ->
                            Text(
                                host.label ?: host.hostname,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize = 14.sp
                                ),
                                modifier =
                                    GlanceModifier.fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .clickable(
                                            actionRunCallback<WakeHostAction>(
                                                actionParametersOf(
                                                    WakeHostAction.HOST_ID_KEY to host.id
                                                )
                                            )
                                        ),
                            )
                        }
                    }
                }
            }
        }
    }
}

class WolWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WolWidget()
}
