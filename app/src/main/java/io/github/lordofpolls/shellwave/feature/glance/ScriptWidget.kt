package io.github.lordofpolls.shellwave.feature.glance

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode
import io.github.lordofpolls.shellwave.service.ScriptTriggerService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * One button per pinned script, a thin wrapper over ScriptTriggerService with no SSH or database
 * logic beyond which scripts and which label - the shape [ShortcutsSync] and `ScriptTileService`
 * share.
 *
 * Taps go straight to the service through `actionStartService` and not `actionRunCallback`, which
 * costs a full process warm-up first: broadcast to [ScriptWidgetReceiver], cold start, Hilt init, a
 * Glance session, an `ActionCallback` coroutine, and only then the service. With
 * `actionStartService` the `PendingIntent` names the service and is built at layout time, so the
 * foreground notification appears sooner. That is why [ScriptTriggerService.intentFor] exists - the
 * intent has to be describable before the process that would build it.
 *
 * Only capture-mode scripts with a fixed target host are shown, even if one was pinned before being
 * edited into something else. ScriptTriggerService would refuse them anyway; filtering here keeps
 * the button from promising what a tap cannot deliver.
 */
class ScriptWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val scriptDao =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).scriptDao()
        val pinnedScripts =
            scriptDao.observeAll().combine(WidgetPreferences.pinnedScriptIdsFlow(context)) {
                    scripts,
                    pinnedIds,
                ->
                scripts.filter {
                    it.id in pinnedIds && it.targetHostId != null && runCatching {
                        ScriptMode.valueOf(
                            it.mode
                        )
                    }.getOrNull() == ScriptMode.CAPTURE
                }
            }
        val initialScripts = pinnedScripts.first()

        provideContent {
            val scripts by pinnedScripts.collectAsState(initialScripts)
            Column(
                modifier = GlanceModifier.fillMaxWidth().background(Color(0xFF1B1B1F))
                    .padding(12.dp),
            ) {
                Text(
                    "Shellwave",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                )
                if (scripts.isEmpty()) {
                    Text(
                        "No scripts pinned - pin one from the Scripts list.",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFAAAAAA)),
                            fontSize = 12.sp
                        ),
                        modifier = GlanceModifier.padding(top = 8.dp),
                    )
                } else {
                    scripts.forEach { script ->
                        Text(
                            // An emoji the user typed into the name renders as typed: their data,
                            // not the app's decoration.
                            script.name,
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp),
                            modifier =
                                GlanceModifier.fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable(
                                        actionStartService(
                                            ScriptTriggerService.intentFor(
                                                context,
                                                script.id
                                            ), isForegroundService = true
                                        )
                                    ),
                        )
                    }
                }
            }
        }
    }
}

class ScriptWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScriptWidget()
}
