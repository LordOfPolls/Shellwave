package io.github.lordofpolls.shellwave.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.GroupEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.net.Reachability
import io.github.lordofpolls.shellwave.core.prefs.HostListPreferences
import io.github.lordofpolls.shellwave.core.prefs.HostSort
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import io.github.lordofpolls.shellwave.core.prefs.rememberPrefState
import io.github.lordofpolls.shellwave.feature.host.hostDeleteBlockReason
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SessionSummary
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import io.github.lordofpolls.shellwave.ui.design.ColorPickerDialog
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.GroupHeader
import io.github.lordofpolls.shellwave.ui.design.HostCard
import io.github.lordofpolls.shellwave.ui.design.HostMoveTarget
import io.github.lordofpolls.shellwave.ui.design.HostScriptAction
import io.github.lordofpolls.shellwave.ui.design.LiveSessionStrip
import io.github.lordofpolls.shellwave.ui.design.LocalReadableInset
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.Wordmark
import kotlinx.coroutines.launch

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
 * The deletion confirmation belongs here. [HostCard] only reports that the user asked. The RESTRICT
 * pre-check that names dependent hosts runs before the dialog, so a blocked delete shows its reason
 * rather than a Delete button; `MainActivity` checks again against the database.
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
    groups: List<GroupEntity> = emptyList(),
    onUpdateGroup: (GroupEntity) -> Unit = {},
    onDeleteGroup: (GroupEntity) -> Unit = {},
    onSetHostPositions: suspend (Map<Long, Int>) -> Unit = {},
    onSetGroupPositions: suspend (Map<Long, Int>) -> Unit = {},
    onMoveToGroup: (HostEntity, groupId: Long?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Hoisted because a LazyColumn item leaving composition would take the user's typing with it.
    var hostPendingDelete by remember { mutableStateOf<HostEntity?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var quickConnectText by rememberSaveable { mutableStateOf("") }
    var groupBeingEdited by remember { mutableStateOf<GroupEntity?>(null) }
    var groupPendingDelete by remember { mutableStateOf<GroupEntity?>(null) }
    val sort by rememberPrefState { HostListPreferences.sort(it) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Bumped on every WOL tile change so the `remember` below re-reads WidgetPreferences.
    var wolTileChanges by remember { mutableIntStateOf(0) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val searchVisible = hosts.size >= SEARCH_FIELD_HOST_THRESHOLD
    val visibleHosts = if (searchVisible) hosts.filter { hostMatches(it, searchQuery) } else hosts
    val searching = searchVisible && searchQuery.isNotBlank()
    // Built from visibleHosts so a header's count is what is shown under it.
    val sections = buildSections(visibleHosts, groups, sort).filter {
        !searching || it.group == null || it.hosts.isNotEmpty()
    }
    // A move while filtered would renumber only the visible rows and collide with the hidden ones.
    val movable = sort == HostSort.MANUAL && !searching
    val groupIds = sections.mapNotNull { it.group?.id }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // MainActivity's Scaffold already applied the status and nav bar insets. This Scaffold only
        // places the top bar, so the default safeDrawing would apply them twice - with it,
        // the title sat about a fifth of the way down the screen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Wordmark() },
                // Same double-inset reasoning as contentWindowInsets above.
                windowInsets = WindowInsets(0, 0, 0, 0),
                // ssh-config import is a once-per-install action at most, which does not earn
                // permanent chrome.
                actions = {
                    IconButton(onClick = onAddHost) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add host")
                    }
                    HostsOverflow(
                        sort = sort,
                        onSort = { picked ->
                            if (picked == HostSort.MANUAL && sort != HostSort.MANUAL) {
                                // Seeded before the pref flips, or the list renders unseeded for a frame.
                                scope.launch {
                                    // Unfiltered, so a search in progress doesn't leave hidden hosts unseeded.
                                    val shown = buildSections(hosts, groups, sort)
                                    onSetGroupPositions(positionsFor(shown.mapNotNull { it.group?.id }))
                                    shown.forEach { onSetHostPositions(positionsFor(it.hosts.map { h -> h.id })) }
                                    HostListPreferences.setSort(context, picked)
                                }
                            } else {
                                HostListPreferences.setSort(context, picked)
                            }
                        },
                        onImportSshConfig = onImportSshConfig,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = ScreenMargin + LocalReadableInset.current,
                end = ScreenMargin + LocalReadableInset.current,
                top = 8.dp,
                bottom = 16.dp
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
                Row(
                    modifier = Modifier.padding(top = SectionGap),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Saved hosts",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        "Sorted: ${sort.label.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
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
            }

            sections.forEach { section ->
                val group = section.group
                val hostIds = section.hosts.map { it.id }
                if (group != null) {
                    item(key = "group-${group.id}") {
                        val index = groupIds.indexOf(group.id)
                        GroupHeader(
                            name = group.name,
                            accent = Color(group.color),
                            count = section.hosts.size,
                            collapsed = group.collapsed && !searching,
                            onToggle = { onUpdateGroup(group.copy(collapsed = !group.collapsed)) },
                            onEdit = { groupBeingEdited = group },
                            onDelete = { groupPendingDelete = group },
                            onMoveUp = if (movable && index > 0) {
                                { scope.launch { onSetGroupPositions(positionsFor(swapped(groupIds, index, -1))) } }
                            } else null,
                            onMoveDown = if (movable && index < groupIds.lastIndex) {
                                { scope.launch { onSetGroupPositions(positionsFor(swapped(groupIds, index, 1))) } }
                            } else null,
                        )
                    }
                } else if (groupIds.isNotEmpty()) {
                    // Without a header of its own the block reads as more rows of the group above it.
                    item(key = "ungrouped") {
                        Text(
                            "Ungrouped",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Lines up with a group's name, past its colour dot.
                            modifier = Modifier.padding(start = 36.dp, top = 8.dp),
                        )
                    }
                }
                if (group == null || searching || !group.collapsed) {
                    itemsIndexed(section.hosts, key = { _, host -> "host-${host.id}" }) { index, host ->
                        val session = liveSessions.firstOrNull { it.hostId == host.id }
                        val isWolTile = remember(host.id, wolTileChanges) {
                            WidgetPreferences.wolTileHostId(context) == host.id
                        }
                        HostCard(
                            displayName = host.label ?: host.hostname,
                            identity = "${host.username}@${host.hostname}" +
                                    if (host.port == 22) "" else ":${host.port}",
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
                            isWolTile = isWolTile,
                            onUseForWolTile = host.macAddress?.let {
                                {
                                    WidgetPreferences.setWolTileHostId(
                                        context,
                                        if (isWolTile) null else host.id
                                    )
                                    wolTileChanges++
                                }
                            },
                            accent = (host.color ?: group?.color)?.let { Color(it) },
                            onMoveUp = if (movable && index > 0) {
                                { scope.launch { onSetHostPositions(positionsFor(swapped(hostIds, index, -1))) } }
                            } else null,
                            onMoveDown = if (movable && index < hostIds.lastIndex) {
                                { scope.launch { onSetHostPositions(positionsFor(swapped(hostIds, index, 1))) } }
                            } else null,
                            onDelete = { hostPendingDelete = host },
                            moveTargets = if (groups.isEmpty()) emptyList() else {
                                (listOf(null) + groups).map { target ->
                                    HostMoveTarget(
                                        name = target?.name ?: "No group",
                                        accent = target?.let { Color(it.color) },
                                        current = host.groupId == target?.id,
                                        onMove = { if (host.groupId != target?.id) onMoveToGroup(host, target?.id) },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    hostPendingDelete?.let { host ->
        val blockReason = hostDeleteBlockReason(host, hosts.filter { it.proxyJumpHostId == host.id })
        if (blockReason != null) {
            AlertDialog(
                onDismissRequest = { hostPendingDelete = null },
                title = { Text("Can't delete host") },
                text = { Text(blockReason) },
                confirmButton = { TextButton(onClick = { hostPendingDelete = null }) { Text("OK") } },
            )
            return@let
        }
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

    groupBeingEdited?.let { group ->
        GroupEditDialog(
            group = group,
            onSave = {
                onUpdateGroup(it)
                groupBeingEdited = null
            },
            onDismiss = { groupBeingEdited = null },
        )
    }

    groupPendingDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupPendingDelete = null },
            title = { Text("Delete group?") },
            text = { Text("Removes \"${group.name}\". Its hosts are kept and become ungrouped.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGroup(group)
                    groupPendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { groupPendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GroupEditDialog(group: GroupEntity, onSave: (GroupEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(group.name) }
    var color by remember { mutableStateOf(group.color) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { picking = true }) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(color)))
                    Text("Colour", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(group.copy(name = name.trim(), color = color)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        ColorPickerDialog(
            initial = color,
            onPick = {
                color = it
                picking = false
            },
            onDismiss = { picking = false },
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
private fun HostsOverflow(
    sort: HostSort,
    onSort: (HostSort) -> Unit,
    onImportSshConfig: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Reset on every open so the menu never reappears mid-submenu.
    var showingSort by remember { mutableStateOf(false) }
    IconButton(onClick = { showingSort = false; expanded = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (showingSort) {
            DropdownMenuItem(
                text = { Text("Back") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                },
                onClick = { showingSort = false },
            )
            HostSort.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    trailingIcon = {
                        if (mode == sort) Icon(Icons.Outlined.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        expanded = false
                        onSort(mode)
                    },
                )
            }
        } else {
            DropdownMenuItem(
                text = { Text("Sort by") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
                trailingIcon = {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                },
                onClick = { showingSort = true },
            )
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
}

/**
 * The button carries an explicit `height` because an `OutlinedTextField` without a floating label
 * is 56dp tall while every M3 `Button` defaults to 40dp; left alone, Connect looks subordinate to
 * the thing it acts on.
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
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.height(QuickConnectFieldHeight),
        ) { Text("Connect") }
    }
}

/** An [OutlinedTextField] with no floating label measures 56dp - see [QuickConnectRow]. */
private val QuickConnectFieldHeight = 56.dp
