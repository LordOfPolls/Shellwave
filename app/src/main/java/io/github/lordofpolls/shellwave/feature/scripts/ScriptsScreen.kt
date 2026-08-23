package io.github.lordofpolls.shellwave.feature.scripts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import io.github.lordofpolls.shellwave.feature.glance.ScriptWidget
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.ScriptCard
import kotlinx.coroutines.launch

/**
 * The Scripts destination. Each row is a [ScriptCard], carrying Run as its one always-visible
 * action with the rest in a menu.
 *
 * Widget and QS-tile state is read and written here rather than inside ScriptCard, for the reason
 * the delete confirmation is here: a `ui/design` component reports intent and renders what it is
 * given, while [WidgetPreferences] and `ScriptWidget.updateAll` are app state.
 *
 * Pinning is offered only for capture-mode scripts, because `ScriptTriggerService` refuses
 * everything else on a background trigger. That refusal is a security boundary, so the UI must not
 * offer to cross it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    scripts: List<ScriptEntity>,
    hosts: List<HostEntity>,
    onRun: (ScriptEntity) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ScriptEntity) -> Unit,
    onDelete: (ScriptEntity) -> Unit,
    onHistory: (ScriptEntity) -> Unit,
    modifier: Modifier = Modifier,
    onAddFromTemplate: (ScriptTemplate) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // One pending delete regardless of which card was tapped: a card reports intent, the screen
    // owns "did you mean it".
    var scriptPendingDelete by remember { mutableStateOf<ScriptEntity?>(null) }

    // WidgetPreferences has no change-notification Flow, so this is re-read through `pinChanges`
    // after every write. A screen-wide counter and not per-script state, so a QS-tile change -
    // which implicitly clears whichever other script held the tile - refreshes every card.
    var pinChanges by remember { mutableStateOf(0) }

    // Owned here rather than by the top bar, because the empty state opens it too.
    var templatesOpen by remember { mutableStateOf(false) }

    if (templatesOpen) {
        ScriptTemplateDialog(
            onPick = { template ->
                templatesOpen = false
                onAddFromTemplate(template)
            },
            onDismiss = { templatesOpen = false },
        )
    }

    Scaffold(
        modifier = modifier,
        // MainActivity's Scaffold already applied the system bar insets without consuming them.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Scripts") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = { templatesOpen = true }) {
                        Icon(
                            Icons.Outlined.LibraryBooks,
                            contentDescription = "Start from a template"
                        )
                    }
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add script")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (scripts.isEmpty()) {
            // The catalogue over the blank editor the "+" already reaches: EmptyState has one
            // action slot, and someone with no scripts is least able to write one from nothing.
            EmptyState(
                message = "No scripts yet.",
                actionLabel = "Start from a template",
                onAction = { templatesOpen = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(scripts, key = { it.id }) { script ->
                    val capture = isCapture(script.mode)
                    // What a widget, tile or shortcut can actually run: capture mode and a fixed
                    // host, since "ask each run" has no picker out there. History stays on
                    // `capture` alone - past runs exist however the host was chosen.
                    val runnableInBackground = capture && script.targetHostId != null
                    val pinned = remember(script.id, pinChanges) {
                        WidgetPreferences.isPinned(
                            context,
                            script.id
                        )
                    }
                    val isQsTile = remember(
                        script.id,
                        pinChanges
                    ) { WidgetPreferences.qsTileScriptId(context) == script.id }

                    ScriptCard(
                        name = script.name,
                        modeAndTarget = scriptModeAndTarget(script, hosts),
                        pinnedToWidget = pinned,
                        isQsTile = isQsTile,
                        onRun = { onRun(script) },
                        onEdit = { onEdit(script) },
                        onDelete = { scriptPendingDelete = script },
                        onHistory = if (capture) ({ onHistory(script) }) else null,
                        onPinToWidget =
                            if (!runnableInBackground) {
                                null
                            } else {
                                {
                                    WidgetPreferences.setPinned(context, script.id, !pinned)
                                    pinChanges++
                                    scope.launch { ScriptWidget().updateAll(context) }
                                }
                            },
                        onUseForQsTile =
                            if (!runnableInBackground) {
                                null
                            } else {
                                {
                                    WidgetPreferences.setQsTileScriptId(
                                        context,
                                        if (isQsTile) null else script.id
                                    )
                                    pinChanges++
                                }
                            },
                    )
                }
            }
        }
    }

    scriptPendingDelete?.let { script ->
        AlertDialog(
            onDismissRequest = { scriptPendingDelete = null },
            title = { Text("Delete script?") },
            text = { Text("Removes \"${script.name}\" and its run history. Can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(script)
                    scriptPendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scriptPendingDelete = null
                }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The machine meta line: mode and target host, as `CAPTURE · betty`. The host resolves to the same
 * `label ?: hostname` every other surface shows, so one host never appears under two names.
 *
 * A `SEND_TO_CURRENT` script has no target host by definition and renders its mode alone rather
 * than a dangling separator. An unrecognised stored mode renders verbatim, matching how
 * [ScriptMode] decoding degrades elsewhere instead of throwing on a hand-edited row.
 *
 * For the other two modes a missing target is "ask each run", whether the user left it unset or
 * Room's `SET_NULL` cleared it after a host was deleted. That phrase stays out of the mono
 * register: the app's wording, not machine truth.
 */
fun scriptModeAndTarget(script: ScriptEntity, hosts: List<HostEntity>): String {
    val decoded = runCatching { ScriptMode.valueOf(script.mode) }.getOrNull()
    val mode = decoded?.name ?: script.mode
    val host = script.targetHostId?.let { id -> hosts.firstOrNull { it.id == id } }
    val target =
        host?.let { it.label ?: it.hostname }
            ?: "Ask each run".takeIf { script.targetHostId == null && decoded != null && decoded != ScriptMode.SEND_TO_CURRENT }
    return if (target == null) mode else "$mode · $target"
}

private fun isCapture(mode: String): Boolean =
    runCatching { ScriptMode.valueOf(mode) }.getOrNull() == ScriptMode.CAPTURE
