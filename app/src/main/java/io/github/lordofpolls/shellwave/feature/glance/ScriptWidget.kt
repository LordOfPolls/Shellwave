package io.github.lordofpolls.shellwave.feature.glance

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
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
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode
import io.github.lordofpolls.shellwave.service.WidgetTrampolineActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

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
                modifier = GlanceModifier.fillMaxSize()
                    .background(GlanceTheme.colors.widgetBackground)
                    .padding(12.dp),
            ) {
                Text(
                    "Shellwave",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    modifier = GlanceModifier.fillMaxWidth()
                        .clickable(actionStartActivity<MainActivity>()),
                )
                if (scripts.isEmpty()) {
                    Text(
                        "No scripts pinned - pin one from the Scripts list.",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp
                        ),
                        modifier = GlanceModifier.padding(top = 8.dp),
                    )
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(scripts, itemId = { it.id }) { script ->
                            Text(
                                script.name,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize = 14.sp
                                ),
                                modifier =
                                    GlanceModifier.fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .clickable(
                                            actionStartActivityIntent(
                                                WidgetTrampolineActivity.intentFor(
                                                    context,
                                                    script.id
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

class ScriptWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScriptWidget()
}
