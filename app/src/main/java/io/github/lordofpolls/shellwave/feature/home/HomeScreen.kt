package io.github.lordofpolls.shellwave.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.net.Reachability
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SessionSummary
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.HostCard
import io.github.lordofpolls.shellwave.ui.design.HostScriptAction
import io.github.lordofpolls.shellwave.ui.design.LiveSessionStrip
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.Wordmark

/** A search field is inserted above the host list at or beyond this many hosts. */
private const val SEARCH_FIELD_HOST_THRESHOLD = 8

private val ScreenMargin = 16.dp

private val SectionGap = 24.dp

/**
 * Recent is a strip of shortcuts to the list right below it and no section competing with it, so a
 * full [SectionGap] would advertise a break the reader does not need. The chips cannot shrink - an
 * AssistChip sits on the 48dp touch-target floor - so chrome is the only thing left to trim.
 */
private val RecentSectionGap = 12.dp

private val RecentInnerGap = 4.dp

/**
 * The app's start screen: live session strip, quick connect, recents, saved hosts.
 *
 * One `LazyColumn` and no `Column` wrapping it, so the app bar can scroll away and quick connect
 * scrolls with the list. That makes the hoisting load-bearing: an item scrolled out of a
 * `LazyColumn` leaves composition, so [searchQuery] and the quick-connect text live in the screen's
 * scope. Inside the item lambdas they would clear whatever the user had typed.
 *
 * The deletion confirmation belongs here. [HostCard] only reports that the user asked; the RESTRICT
 * pre-check that names dependent hosts is another level up in `MainActivity`.
 *
 * [recents] is recently connected saved hosts. Quick-connect history is something this app does not
 * store. A chip that merely refilled the field would ignore the host's saved credential and prompt
 * for a password, making a saved host connect worse from the shortcut than from its own card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    hosts: List<HostEntity>,
    recents: List<HostEntity>,
    scripts: List<ScriptEntity> = emptyList(),
    liveSessions: List<SessionSummary> = emptyList(),
    /** Probe results by host id. Empty and ignored unless [reachabilityEnabled]. */
    reachability: Map<Long, Reachability> = emptyMap(),
    /** The setting itself; the readings are [reachability]. */
    reachabilityEnabled: Boolean = false,
    onQuickConnect: (QuickConnectTarget) -> Unit,
    onConnectSaved: (HostEntity) -> Unit,
    onOpenSession: (Long) -> Unit = {},
    onOpenSessionList: () -> Unit = {},
    onAddHost: () -> Unit,
    onEditHost: (HostEntity) -> Unit,
    onDeleteHost: (HostEntity) -> Unit,
    onDuplicateHost: (HostEntity) -> Unit = {},
    onWakeHost: (HostEntity) -> Unit = {},
    onRunScript: (ScriptEntity, HostEntity) -> Unit = { _, _ -> },
    onImportSshConfig: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Hoisted because a LazyColumn item leaving composition would take the user's typing with it.
    var hostPendingDelete by remember { mutableStateOf<HostEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var quickConnectText by remember { mutableStateOf("") }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val searchVisible = hosts.size >= SEARCH_FIELD_HOST_THRESHOLD
    val visibleHosts = if (searchVisible) hosts.filter { hostMatches(it, searchQuery) } else hosts

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // MainActivity's Scaffold already applied the status and nav bar insets. This Scaffold only
        // places the top bar and FAB, so the default safeDrawing would apply them twice - with it,
        // the title sat about a fifth of the way down the screen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Wordmark() },
                // Same double-inset reasoning as contentWindowInsets above.
                windowInsets = WindowInsets(0, 0, 0, 0),
                // ssh-config import is a once-per-install action at most, which does not earn
                // permanent chrome.
                actions = { HostsOverflow(onImportSshConfig = onImportSshConfig) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost) {
                Icon(Icons.Outlined.Add, contentDescription = "Add host")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = ScreenMargin,
                end = ScreenMargin,
                top = 8.dp,
                bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "liveStrip") {
                LiveSessionStrip(
                    sessions = liveSessions,
                    onOpenSession = onOpenSession,
                    onOpenSessionList = onOpenSessionList,
                )
            }

            item(key = "quickConnect") {
                QuickConnectRow(
                    text = quickConnectText,
                    onTextChange = { quickConnectText = it },
                    onConnect = onQuickConnect,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (recents.isNotEmpty()) {
                item(key = "recents") {
                    Column(
                        modifier = Modifier.padding(top = RecentSectionGap),
                        verticalArrangement = Arrangement.spacedBy(RecentInnerGap),
                    ) {
                        Text("Recent", style = MaterialTheme.typography.titleSmall)
                        // FlowRow over a LazyRow: a horizontally scrolling row hides its overflow
                        // off the right edge. Capped at one line - the chips are on the 48dp floor,
                        // so once the padding came out the second line was the only height left to
                        // reclaim, and keeping saved hosts on the first screenful matters more.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(RecentInnerGap),
                            maxLines = 1,
                        ) {
                            recents.forEach { host ->
                                AssistChip(
                                    onClick = { onConnectSaved(host) },
                                    // Resolved exactly as HostCard resolves its title. A host with
                                    // no label falls back to its hostname, which is machine truth,
                                    // hence MachineText on that branch and not the labelled one.
                                    label = {
                                        val label = host.label
                                        if (label != null) Text(label) else MachineText(host.hostname)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "savedHostsHeader") {
                Text(
                    "Saved hosts",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = SectionGap),
                )
            }

            if (searchVisible) {
                item(key = "search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search hosts") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (hosts.isEmpty()) {
                item(key = "empty") {
                    // Quick connect stays above this: an empty saved-host list is not an empty app.
                    EmptyState(
                        message = "No saved hosts yet.",
                        actionLabel = "Add host",
                        onAction = onAddHost
                    )
                }
            } else if (visibleHosts.isEmpty()) {
                item(key = "noMatch") { EmptyState(message = "No hosts match \"$searchQuery\".") }
            } else {
                items(visibleHosts, key = { it.id }) { host ->
                    val session = liveSessions.firstOrNull { it.hostId == host.id }
                    HostCard(
                        displayName = host.label ?: host.hostname,
                        identity = "${host.username}@${host.hostname}:${host.port}",
                        // A CLOSED or FAILED session is not a state of the host - it belongs on the
                        // Sessions screen, which has room for the error and the Reconnect action.
                        liveStatus = session?.status?.takeIf { it in TRANSITIONAL_OR_LIVE },
                        // Null when the probe is off, so the card renders as if the feature did not
                        // exist. A probed host with no entry yet is UNKNOWN rather than absent: a
                        // missing marker would read as "not applicable" instead of "not yet".
                        reachability = if (reachabilityEnabled) reachability[host.id]
                            ?: Reachability.UNKNOWN else null,
                        scripts = hostScriptActions(host, scripts, onRunScript),
                        onClick = {
                            if (session != null) onOpenSession(session.id) else onConnectSaved(host)
                        },
                        onEdit = { onEditHost(host) },
                        onDuplicate = { onDuplicateHost(host) },
                        onWake = host.macAddress?.let { { onWakeHost(host) } },
                        onDelete = { hostPendingDelete = host },
                    )
                }
            }
        }
    }

    hostPendingDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { hostPendingDelete = null },
            title = { Text("Delete host?") },
            text = {
                Text(
                    "Removes \"${host.label ?: host.hostname}\" and its port forwards. Scripts targeting " +
                            "it will be left without a host. Can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteHost(host)
                    hostPendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    hostPendingDelete = null
                }) { Text("Cancel") }
            },
        )
    }
}

/** Live or transitioning - the only states a [HostCard] shows a marker for. */
private val TRANSITIONAL_OR_LIVE =
    setOf(SessionStatus.CONNECTED, SessionStatus.CONNECTING, SessionStatus.RECONNECTING)

/**
 * `SEND_TO_CURRENT` scripts are excluded: they type into whichever session is focused, so offering
 * one from a host's menu would offer an action that ignores the host it was launched from.
 *
 * A host-agnostic script appears on every card and runs against this card's host. Stopping to ask
 * which host would be asking a question the user answered by opening that host's menu.
 */
internal fun hostScriptActions(
    host: HostEntity,
    scripts: List<ScriptEntity>,
    onRunScript: (ScriptEntity, HostEntity) -> Unit,
): List<HostScriptAction> =
    scripts
        .filter {
            (it.targetHostId == host.id || it.targetHostId == null) && runCatching {
                ScriptMode.valueOf(
                    it.mode
                )
            }.getOrNull() != ScriptMode.SEND_TO_CURRENT
        }
        .map { script ->
            HostScriptAction(
                id = script.id,
                name = script.name,
                onRun = { onRunScript(script, host) })
        }

@Composable
private fun HostsOverflow(onImportSshConfig: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Import from ~/.ssh/config") },
            leadingIcon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
            onClick = {
                expanded = false
                onImportSshConfig()
            },
        )
    }
}

/**
 * The button carries an explicit `height` because an `OutlinedTextField` without a floating label
 * is 56dp tall while every M3 `Button` defaults to 40dp; left alone, Connect looks subordinate to
 * the thing it acts on. Tonal is the emphasis tier the add-host FAB leaves free.
 *
 * The placeholder goes through MachineText. The typed value cannot - that component wraps `Text`,
 * not `BasicTextField` - so the mono face is applied via `textStyle`, the one place in the app
 * where that is necessary.
 */
@Composable
private fun QuickConnectRow(
    text: String,
    onTextChange: (String) -> Unit,
    onConnect: (QuickConnectTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = parseQuickConnect(text)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { MachineText("user@host:port") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = ChromeMonoFontFamily),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { target?.let(onConnect) }),
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = { target?.let(onConnect) },
            enabled = target != null,
            modifier = Modifier.height(QuickConnectFieldHeight),
        ) { Text("Connect") }
    }
}

/** An [OutlinedTextField] with no floating label measures 56dp - see [QuickConnectRow]. */
private val QuickConnectFieldHeight = 56.dp
