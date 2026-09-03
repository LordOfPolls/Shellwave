package io.github.lordofpolls.shellwave.feature.session

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import io.github.lordofpolls.shellwave.core.db.dao.KeyBarLayoutDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.core.prefs.SessionLayoutPreferences
import io.github.lordofpolls.shellwave.core.prefs.rememberPrefState
import io.github.lordofpolls.shellwave.core.ui.FoldPosture
import io.github.lordofpolls.shellwave.core.ui.rememberFoldPosture
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ssh.TerminalSize
import io.github.lordofpolls.shellwave.terminal.DEFAULT_KEY_BAR_KEYS
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_TEXT_SIZE_SP
import io.github.lordofpolls.shellwave.terminal.FONT_SIZE_SP_RANGE
import io.github.lordofpolls.shellwave.terminal.KeyBar
import io.github.lordofpolls.shellwave.terminal.LinkType
import io.github.lordofpolls.shellwave.terminal.TerminalCanvas
import io.github.lordofpolls.shellwave.terminal.TerminalFontFamily
import io.github.lordofpolls.shellwave.terminal.TerminalInputCapture
import io.github.lordofpolls.shellwave.terminal.TerminalLink
import io.github.lordofpolls.shellwave.terminal.TerminalMatch
import io.github.lordofpolls.shellwave.terminal.TerminalSelectionOverlay
import io.github.lordofpolls.shellwave.terminal.collectTerminalRows
import io.github.lordofpolls.shellwave.terminal.ctrlCode
import io.github.lordofpolls.shellwave.terminal.decodeKeyBarKeys
import io.github.lordofpolls.shellwave.terminal.externalRowAt
import io.github.lordofpolls.shellwave.terminal.findMatches
import io.github.lordofpolls.shellwave.terminal.hardwareKeyCode
import io.github.lordofpolls.shellwave.terminal.linkAt
import io.github.lordofpolls.shellwave.terminal.rememberTerminalSelectionState
import io.github.lordofpolls.shellwave.terminal.resolveTerminalTypeface
import io.github.lordofpolls.shellwave.terminal.selectionHighlightColor
import io.github.lordofpolls.shellwave.terminal.terminalGestures
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.SessionChipModel
import io.github.lordofpolls.shellwave.ui.design.SessionChipRail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * N concurrent sessions, with a [ListDetailPaneScaffold] beside the terminal on wide or unfolded
 * windows and a dedicated split for tabletop posture. Session state lives in [sessionManager], so a
 * rotation or a fold can tear this screen down and rebuild it without touching a connection.
 *
 * Switching is by tab. A `HorizontalPager` cannot work: `terminalGestures` claims every touch in
 * the content area on [androidx.compose.ui.input.pointer.PointerEventPass.Initial], so a horizontal
 * swipe there never reaches a pager. Swipe-to-switch would mean reworking that touch ownership on
 * purpose.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SessionsScreen(
    sessionManager: SessionManager,
    // SessionSummary carries a hostId, not the host row. A session whose host can't be resolved
    // still renders, falling back to the host portion of its own identity string.
    hosts: List<HostEntity> = emptyList(),
    onNewSession: () -> Unit,
    initialSessionId: Long? = null,
    // No fixed host: typed into whichever session is focused here.
    sendToCurrentScripts: List<ScriptEntity> = emptyList(),
    // Unfiltered: the filtering that matters is per-session, and only this screen knows the
    // selected session's host. See [runnableHere].
    captureScripts: List<ScriptEntity> = emptyList(),
    onRunScript: (ScriptEntity, SshConnection) -> Unit = { _, _ -> },
    // The session id travels alongside the connection: MainActivity resolves it back to a
    // connection itself once the browser screen is pushed, since a session can close (and its
    // SshConnection with it) while that screen is open.
    onBrowseFiles: (sessionId: Long, connection: SshConnection) -> Unit = { _, _ -> },
    // Null before any profile row has ever been saved, in which case DEFAULT_TERMINAL_PROFILE
    // applies. A host with its own override uses that instead, resolved per-session in
    // `SessionTabBody` - so this default stays live while an override, once resolved, does not.
    terminalProfile: TerminalProfileEntity? = null,
    terminalProfileDao: TerminalProfileDao,
    keyBarLayoutDao: KeyBarLayoutDao,
    modifier: Modifier = Modifier,
) {
    val summaries by sessionManager.summaries.collectAsState()
    if (summaries.isEmpty()) {
        // Was onEmpty(), which popped the screen away and made an empty Sessions tab unreachable
        // rather than idle. Copy matches SessionsListScreen's empty state.
        EmptyState(
            message = "No open sessions.",
            actionLabel = "Open Hosts",
            onAction = onNewSession,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val scope = rememberCoroutineScope()

    // Threaded down so the path-tap dialog's "Download" reaches it too, beyond the menu actions.
    val fileTransferController = rememberFileTransferController()
    val loggingController = rememberSessionLoggingController()

    // Driven by the navigator's own destination; a second piece of state could drift from it.
    // Falls back to the first session once a target is known to exist, so a freshly-opened screen
    // - or one whose selected session just closed - never shows an empty detail pane.
    val selectedId = navigator.currentDestination?.contentKey
        ?: summaries.firstOrNull { it.id == initialSessionId }?.id ?: summaries.first().id

    // Keyed on the selected session, not shared screen-wide: switching tabs must not leave the
    // bar open over a session that was never searched.
    val searchController = rememberTerminalSearchController(selectedId)

    fun selectSession(id: Long) {
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }
    }

    // Keyed on [initialSessionId] instead of `Unit`: the terminal is not always torn down between
    // two requests, since `openTerminal` is idempotent when it is already on top, so a session
    // opened while one is showing changes this parameter without recomposing the screen from
    // scratch. With `Unit` that request was silently dropped and the previous session stayed put.
    LaunchedEffect(initialSessionId) {
        selectSession(
            summaries.firstOrNull { it.id == initialSessionId }?.id ?: selectedId
        )
    }

    val bellContext = LocalContext.current
    val bellMode by rememberPrefState { BellPreferences.get(it) }

    // WindowInfoTracker reports per-Activity window metrics. Degrades to Flat rather than crashing
    // without one, e.g. in a @Preview.
    val activity = LocalActivity.current
    val posture = if (activity != null) rememberFoldPosture(activity).value else FoldPosture.Flat

    val layoutContext = LocalContext.current
    val fullWidthTerminal by rememberPrefState { SessionLayoutPreferences.getFullWidthTerminal(it) }

    // On a narrow window there is no second pane to collapse, so the full-width toggle must be
    // absent rather than present-but-inert.
    val isWide = navigator.scaffoldDirective.maxHorizontalPartitions >= 2

    Column(modifier = modifier.fillMaxSize()) {
        val current = summaries.firstOrNull { it.id == selectedId }

        // Hoisted above the rail, whose overflow opens it.
        var scriptPickerOpen by remember(current?.id) { mutableStateOf(false) }

        // A capture script pinned to another host is left out: "run it here" and "run it on that
        // other machine" are opposite instructions, and honouring the script's own target would run
        // it somewhere the user is not looking. Hostless ones qualify everywhere.
        val runnableHere =
            remember(sendToCurrentScripts, captureScripts, current?.hostId) {
                sendToCurrentScripts + captureScripts.filter { it.targetHostId == null || it.targetHostId == current?.hostId }
            }

        // The rail is the terminal's whole top strip. Three rows died here - a status header
        // restating the rail's own status word, a permanent bell toggle, and always-visible
        // Download/Upload links. One MoreVert replaced them.
        SessionChipRail(
            sessions =
                summaries.map { summary ->
                    SessionChipModel(
                        id = summary.id,
                        name = sessionDisplayName(summary.hostId, summary.label, hosts),
                        status = summary.status,
                    )
                },
            selectedId = selectedId,
            onSelect = ::selectSession,
            onClose = sessionManager::closeSession,
            onNewSession = onNewSession,
            overflow = {
                // Close is the exception: closing a session that never connected is exactly
                // what a user needs when one is stuck.
                val overflowConnection =
                    current?.connection?.takeIf { current.status == SessionStatus.CONNECTED }
                // current.connection, not the CONNECTED-gated overflowConnection: "Stop logging"
                // must stay reachable through a drop or reconnect, not just while the channel that
                // started it is still up. Memoized per session id, not a fresh fallback flow on
                // every recomposition.
                val loggingFlow = remember(current?.id) {
                    current?.connection?.isLogging ?: MutableStateFlow(false)
                }
                val isLogging by loggingFlow.collectAsState()
                TerminalOverflowMenu(
                    connection = overflowConnection,
                    bellMode = bellMode,
                    onBellMode = { mode -> BellPreferences.set(bellContext, mode) },
                    onDownload = { connection -> fileTransferController.requestDownload(connection) },
                    onUpload = { connection -> fileTransferController.requestUpload(connection) },
                    onBrowseFiles = { connection ->
                        current?.let { onBrowseFiles(it.id, connection) }
                    },
                    onRunScript = if (runnableHere.isEmpty()) null else ({
                        scriptPickerOpen = true
                    }),
                    onClose = current?.let { { sessionManager.closeSession(it.id) } },
                    onSearch = searchController::show,
                    isLogging = isLogging,
                    onStartLogging = loggingController::requestStart,
                    onStopLogging = { current?.connection?.stopLogging() },
                )
            },
        )

        current?.takeIf { it.status == SessionStatus.FAILED }?.error?.let { error ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { sessionManager.reconnectNow(current.id) }) { Text("Retry") }
            }
        }

        if (scriptPickerOpen && current != null) {
            ScriptPickerDialog(
                scripts = runnableHere,
                onPick = { script ->
                    scriptPickerOpen = false
                    onRunScript(script, current.connection)
                },
                onDismiss = { scriptPickerOpen = false },
            )
        }

        SessionsSplitLayout(
            posture = posture,
            isWide = isWide,
            fullWidthTerminal = fullWidthTerminal,
            onToggleFullWidth = {
                SessionLayoutPreferences.setFullWidthTerminal(layoutContext, !fullWidthTerminal)
            },
            navigator = navigator,
            selectedId = selectedId,
            onSelect = ::selectSession,
            summaries = summaries,
            hosts = hosts,
            sessionManager = sessionManager,
            terminalProfile = terminalProfile,
            terminalProfileDao = terminalProfileDao,
            keyBarLayoutDao = keyBarLayoutDao,
            fileTransferController = fileTransferController,
            searchController = searchController,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        )
    }

    // Once for the screen: an AlertDialog overlays everything wherever it is composed.
    FileTransferDialogs(fileTransferController)
    SessionLoggingEffects(loggingController)
}

/**
 * One session's terminal and key bar. Reads live state from [sessionManager]; owns nothing that
 * would need saving across rotation.
 *
 * [terminalHeight] and [preKeyBarGap] are tabletop posture's hook: the terminal is sized exactly
 * instead of sharing space via `weight(1f)`, and the gap reserves the hinge's occluded height. The
 * defaults reproduce the ordinary layout.
 */
// WindowInsets.isImeVisible is @ExperimentalLayoutApi - the key bar's keyboard button needs to know
// whether the IME is up to decide between showing and hiding it, and there is no stable equivalent.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionTabBody(
    sessionId: Long,
    sessionManager: SessionManager,
    terminalProfileDao: TerminalProfileDao,
    keyBarLayoutDao: KeyBarLayoutDao,
    fileTransferController: FileTransferController,
    searchController: TerminalSearchController,
    modifier: Modifier = Modifier,
    terminalProfile: TerminalProfileEntity? = null,
    terminalHeight: Dp? = null,
    preKeyBarGap: Dp = 0.dp,
    showLayoutToggle: Boolean = false,
    fullWidthTerminal: Boolean = false,
    onToggleFullWidth: () -> Unit = {},
) {
    val summaries by sessionManager.summaries.collectAsState()
    val summary = summaries.firstOrNull { it.id == sessionId } ?: return
    val focusRequester = remember { FocusRequester() }

    // The keyboard button's two halves live here, not in KeyBar, because this is what owns the
    // focus: TerminalInputCapture's shim is the only focusable thing on the screen, and an IME with
    // nothing focused has nowhere to send what it types. Showing the keyboard is focus then show,
    // in that order - requestFocus() on an already-focused field is a no-op, which is why the
    // button cannot be that call alone.
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    // Once per session and override id rather than as a live Flow: nothing edits a host's override
    // while that host's session is open, unlike the app-wide default.
    var overrideProfile by remember { mutableStateOf<TerminalProfileEntity?>(null) }
    LaunchedEffect(summary.terminalProfileId) {
        overrideProfile = summary.terminalProfileId?.let { terminalProfileDao.getById(it) }
    }
    // Unlike cursor style, blink and scrollback - baked into the connection at connect() time -
    // these touch only this composable's renderer, so they apply live with no reconnect.
    val profileOrDefault = overrideProfile ?: terminalProfile ?: DEFAULT_TERMINAL_PROFILE
    // Only the selection tint is read here; every other colour comes from emulator.mColors, kept in
    // sync by SessionManager.applyDefaultColorScheme.
    val selectionColor = summary.colorScheme.selectionHighlightColor()
    val fontFamily = TerminalFontFamily.fromStored(profileOrDefault.fontFamily)

    var overrideLayout by remember { mutableStateOf<KeyBarLayoutEntity?>(null) }
    LaunchedEffect(summary.keyBarLayoutId) {
        overrideLayout = summary.keyBarLayoutId?.let { keyBarLayoutDao.getById(it) }
    }
    val keyBarKeys = overrideLayout?.let { decodeKeyBarKeys(it.keysJson) } ?: DEFAULT_KEY_BAR_KEYS
    // From the same per-host layout override the keys come from. No override means one row.
    val keyBarRows = overrideLayout?.rows ?: 1
    val fontContext = LocalContext.current
    val terminalTypeface =
        remember(fontFamily, profileOrDefault.customFontUri) {
            resolveTerminalTypeface(fontContext, fontFamily, profileOrDefault.customFontUri)
        }
    val lineHeightMultiplier = profileOrDefault.lineHeightMultiplier

    var terminalEmulator by remember { mutableStateOf<TerminalEmulator?>(null) }
    var measuredSize by remember { mutableStateOf<TerminalSize?>(null) }
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }

    // topRow <= 0 is how far into the scrollback the view is; scrollDragRemainderPx carries a
    // drag's sub-cell remainder between events, so slow swipes accumulate to a full row instead of
    // rounding to zero every frame.
    var topRow by remember { mutableStateOf(0) }
    var scrollDragRemainderPx by remember { mutableStateOf(0f) }

    var searchQuery by remember(sessionId) { mutableStateOf("") }
    var searchMatches by remember(sessionId) { mutableStateOf(emptyList<TerminalMatch>()) }
    var searchMatchIndex by remember(sessionId) { mutableIntStateOf(0) }

    fun jumpToSearchMatch(index: Int) {
        val matches = searchMatches
        val emulator = terminalEmulator
        if (matches.isEmpty() || emulator == null) return
        val clamped = ((index % matches.size) + matches.size) % matches.size
        searchMatchIndex = clamped
        val maxScrollback = emulator.screen.activeTranscriptRows
        topRow = externalRowAt(emulator, matches[clamped].row).coerceIn(-maxScrollback, 0)
    }

    var fontSizeSp by remember { mutableStateOf(DEFAULT_TERMINAL_TEXT_SIZE_SP) }
    // Keyed on the row's id alone, so a later settings edit doesn't stomp an in-progress
    // pinch-to-resize.
    LaunchedEffect(profileOrDefault.id) { fontSizeSp = profileOrDefault.fontSizeSp.sp }

    // So onMouseDrag only sends on a cell change instead of flooding the socket per pixel.
    var lastMouseCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Coordinates are the external row system, same as topRow.
    val selectionState = rememberTerminalSelectionState()

    var detectedLink by remember { mutableStateOf<TerminalLink?>(null) }

    val redrawRequests = remember { Channel<Unit>(Channel.CONFLATED) }
    val frame = remember { mutableIntStateOf(0) }

    // A fresh query or opening the bar jumps to the first match - the one case where scrolling
    // the view is wanted.
    LaunchedEffect(searchController.open, searchQuery) {
        val emulator = terminalEmulator
        val matches =
            if (searchController.open && emulator != null && searchQuery.isNotEmpty()) {
                findMatches(collectTerminalRows(emulator), searchQuery)
            } else {
                emptyList()
            }
        searchMatches = matches
        if (matches.isNotEmpty()) jumpToSearchMatch(0) else searchMatchIndex = 0
    }

    // Live output re-runs the search sampled - not debounced, for the same reason screenText
    // below is: a build log with no pause would never let debounce emit. Only refreshes the
    // match list, never touches topRow, or output the user isn't scrolled back to look at would
    // keep yanking the view to a stale match. collectTerminalRows touches the emulator and must
    // stay on Main (see SshConnection's threading note); only the string search itself moves off
    // it.
    LaunchedEffect(sessionId) {
        snapshotFlow { frame.intValue }.sample(300).collect {
            if (!searchController.open || searchQuery.isEmpty()) return@collect
            val emulator = terminalEmulator ?: return@collect
            val rows = collectTerminalRows(emulator)
            val matches = withContext(Dispatchers.Default) { findMatches(rows, searchQuery) }
            searchMatches = matches
            searchMatchIndex = searchMatchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(Unit) {
        for (unused in redrawRequests) {
            withFrameNanos { }
            frame.intValue++
        }
    }

    // Rate-limited to one announcement per 400ms so a screen reader isn't flooded per tick - sample
    // over debounce because a build log with no pause would otherwise never let debounce emit.
    var screenText by remember { mutableStateOf("") }
    LaunchedEffect(summary.connection) {
        summary.connection.outputTick.sample(400).collect {
            val emulator = terminalEmulator
            screenText =
                emulator?.screen?.getSelectedText(0, 0, emulator.mColumns, emulator.mRows)
                    ?.trimEnd() ?: ""
        }
    }

    // Re-runs on a fresh SshConnection identity: first connect, or a reconnect.
    LaunchedEffect(summary.connection) {
        summary.connection.outputTick.collect {
            redrawRequests.trySend(Unit)
            // New output while scrolled back must not yank the view to the bottom, nor silently
            // show stale content. When the live screen scrolls, row 0 in the external coordinate
            // system moves with it, so a fixed topRow would drift to point at different history
            // than the user was looking at. getScrollCounter() is that delta since it was last
            // cleared; shift topRow by it, then always clear it so it carries nothing into the next
            // scroll.
            val emulator = terminalEmulator
            if (emulator != null) {
                val scrolled = emulator.scrollCounter
                if (scrolled != 0) {
                    if (topRow < 0) {
                        topRow =
                            (topRow - scrolled).coerceIn(-emulator.screen.activeTranscriptRows, 0)
                    }
                    // The selection points into the same buffer rows - shift it the same way, or it
                    // grabs different text than what's highlighted once the screen scrolls.
                    selectionState.shiftForScroll(scrolled)
                }
                emulator.clearScrollCounter()
            }
        }
    }

    LaunchedEffect(summary.connection, summary.status) {
        if (summary.status == SessionStatus.CONNECTED) {
            terminalEmulator = summary.connection.emulatorOrNull
            redrawRequests.trySend(Unit)
        } else if (summary.status == SessionStatus.CONNECTING) {
            // Fresh connection and emulator: drop the old buffer and anything pointing into it.
            terminalEmulator = null
            topRow = 0
            selectionState.clear()
        }
    }

    // TerminalEmulator has no internal blink timer, only the boolean, so this loop is the schedule.
    // setCursorBlinkingEnabled is re-applied live rather than trusting what connect() baked in, so
    // toggling the setting reaches an open session - unlike cursor style and scrollback depth,
    // which the engine reads only at connection time.
    LaunchedEffect(terminalEmulator, profileOrDefault.cursorBlink) {
        val emulator = terminalEmulator ?: return@LaunchedEffect
        emulator.setCursorBlinkingEnabled(profileOrDefault.cursorBlink)
        if (!profileOrDefault.cursorBlink) return@LaunchedEffect
        var visible = true
        while (isActive) {
            delay(530)
            visible = !visible
            emulator.setCursorBlinkState(visible)
            redrawRequests.trySend(Unit)
        }
    }

    // beginConnectIfNeeded no-ops after the first call, so recomposition calling it again is fine.
    LaunchedEffect(sessionId) {
        val first = snapshotFlow { measuredSize }.filterNotNull().first()
        sessionManager.beginConnectIfNeeded(
            sessionId,
            first.cols,
            first.rows,
            first.cellWidthPx,
            first.cellHeightPx
        )
    }

    LaunchedEffect(summary.connection) {
        snapshotFlow { measuredSize }.filterNotNull().debounce(150).collect { size ->
            summary.connection.resize(size.cols, size.rows, size.cellWidthPx, size.cellHeightPx)
            sessionManager.updateSize(
                sessionId,
                size.cols,
                size.rows,
                size.cellWidthPx,
                size.cellHeightPx
            )
        }
    }

    fun sendText(text: String) {
        val toSend =
            when {
                ctrlLatched -> {
                    ctrlLatched = false
                    text.map { ctrlCode(it) ?: it }.joinToString("")
                }

                altLatched -> {
                    altLatched = false
                    "\u001B$text"
                }

                else -> text
            }.replace('\n', '\r')
        summary.connection.write(toSend)
    }

    fun sendSpecialKey(keyCode: Int) {
        val emulator = summary.connection.emulatorOrNull
        val keyMode =
            (if (ctrlLatched) KeyHandler.KEYMOD_CTRL else 0) or (if (altLatched) KeyHandler.KEYMOD_ALT else 0)
        ctrlLatched = false
        altLatched = false
        val code =
            KeyHandler.getCode(
                keyCode,
                keyMode,
                emulator?.isCursorKeysApplicationMode ?: false,
                emulator?.isKeypadApplicationMode ?: false,
            )
        if (code != null) summary.connection.write(code)
    }

    // TerminalEmulator.sendMouseEvent picks SGR 1006 vs. legacy X10 internally from DECSET state;
    // these three only convert a pixel offset to the 1-based column and row it expects.
    fun mouseCell(offset: Offset): Pair<Int, Int>? {
        val size = measuredSize ?: return null
        return (offset.x / size.cellWidthPx).toInt() + 1 to (offset.y / size.cellHeightPx).toInt() + 1
    }

    fun sendMousePress(offset: Offset) {
        val emulator = terminalEmulator ?: return
        val cell = mouseCell(offset) ?: return
        lastMouseCell = cell
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, cell.first, cell.second, true)
    }

    fun sendMouseDrag(offset: Offset) {
        val emulator = terminalEmulator ?: return
        val cell = mouseCell(offset) ?: return
        if (cell == lastMouseCell) return
        lastMouseCell = cell
        emulator.sendMouseEvent(
            TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED,
            cell.first,
            cell.second,
            true
        )
    }

    fun sendMouseRelease(offset: Offset) {
        val emulator = terminalEmulator ?: return
        val cell = mouseCell(offset) ?: lastMouseCell ?: return
        lastMouseCell = null
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, cell.first, cell.second, false)
    }

    val localContext = LocalContext.current

    fun shareSelection(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(
            Intent.EXTRA_TEXT,
            text
        )
        }
        localContext.startActivity(Intent.createChooser(send, null))
    }

    // A bare IP has no scheme, so "Open" guesses http:// the way an address bar does. A bare path
    // is a path on the SSH host, which is why LinkActionDialog never offers "Open" for
    // LinkType.PATH.
    fun openLink(link: TerminalLink) {
        val uri =
            if (link.type == LinkType.IP) Uri.parse("http://${link.text}") else Uri.parse(link.text)
        runCatching { localContext.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    // imePadding lives on KeyBar itself below, not on this Column - see that call site.
    Column(modifier = modifier) {
        if (searchController.open) {
            TerminalSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = searchMatches.size,
                matchIndex = searchMatchIndex,
                onNext = { jumpToSearchMatch(searchMatchIndex + 1) },
                onPrevious = { jumpToSearchMatch(searchMatchIndex - 1) },
                onClose = { searchController.hide() },
            )
        }
        Box(
            modifier =
                if (terminalHeight != null) {
                    Modifier
                        .height(terminalHeight)
                        .fillMaxWidth()
                } else {
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // Preview rather than onKeyEvent: claims the event before
                    // TerminalInputCapture's BasicTextField, so Ctrl+C isn't first eaten by the
                    // field's own copy shortcut.
                    .onPreviewKeyEvent { event ->
                        val emulator = terminalEmulator
                        val bytes =
                            hardwareKeyCode(
                                event = event,
                                cursorApp = emulator?.isCursorKeysApplicationMode ?: false,
                                keypadApp = emulator?.isKeypadApplicationMode ?: false,
                            )
                        if (bytes != null) {
                            summary.connection.write(bytes)
                            true
                        } else {
                            false
                        }
                    }
                    .terminalGestures(
                        onDrag = { dy ->
                            val cellHeightPx = measuredSize?.cellHeightPx ?: return@terminalGestures
                            val maxScrollback = terminalEmulator?.screen?.activeTranscriptRows ?: 0
                            scrollDragRemainderPx += dy
                            val rows = (scrollDragRemainderPx / cellHeightPx).toInt()
                            if (rows != 0) {
                                scrollDragRemainderPx -= rows * cellHeightPx
                                topRow = (topRow - rows).coerceIn(-maxScrollback, 0)
                            }
                        },
                        onPinch = { scale ->
                            fontSizeSp = (fontSizeSp.value * scale).coerceIn(
                                FONT_SIZE_SP_RANGE.start,
                                FONT_SIZE_SP_RANGE.endInclusive
                            ).sp
                        },
                        onTap = { offset ->
                            val emulator = terminalEmulator
                            val size = measuredSize
                            val link =
                                if (emulator != null && size != null) {
                                    val col = (offset.x / size.cellWidthPx).toInt()
                                        .coerceIn(0, emulator.mColumns - 1)
                                    val screenRow = (offset.y / size.cellHeightPx).toInt()
                                        .coerceIn(0, emulator.mRows - 1)
                                    linkAt(emulator, screenRow + topRow, col)
                                } else {
                                    null
                                }
                            if (link != null) {
                                detectedLink = link
                            } else {
                                focusRequester.requestFocus()
                                selectionState.clear() // tapping outside a selection dismisses it, same as Android's own text selection
                            }
                        },
                        onLongPress = { offset ->
                            val emulator = terminalEmulator
                            val size = measuredSize
                            if (emulator != null && size != null) {
                                selectionState.selectWord(
                                    emulator,
                                    size.cellWidthPx,
                                    size.cellHeightPx,
                                    topRow,
                                    offset
                                )
                            }
                        },
                        isMouseReportingActive = {
                            terminalEmulator?.isMouseTrackingActive() ?: false
                        },
                        onMousePress = ::sendMousePress,
                        onMouseDrag = ::sendMouseDrag,
                        onMouseRelease = ::sendMouseRelease,
                    ),
            ) {
                TerminalCanvas(
                    emulator = terminalEmulator,
                    frame = frame,
                    onMeasured = { cols, rows, cellW, cellH ->
                        measuredSize = TerminalSize(cols, rows, cellW, cellH)
                    },
                    modifier = Modifier.matchParentSize(),
                    fontSizeSp = fontSizeSp,
                    typeface = terminalTypeface,
                    lineHeightMultiplier = lineHeightMultiplier,
                    topRow = topRow,
                    selection = selectionState.selection,
                    selectionColor = selectionColor,
                )
                TerminalInputCapture(
                    focusRequester = focusRequester,
                    onText = ::sendText,
                    onBackspace = { summary.connection.write("\u007F") },
                    // See TerminalInputCapture for why the field carries this instead of the canvas.
                    // Named per session with the label the chip rail shows, so announcement and screen
                    // agree.
                    accessibilityLabel = "Terminal, ${summary.label}",
                    screenText = screenText,
                    modifier = Modifier.matchParentSize(),
                )
                TerminalSelectionOverlay(
                    state = selectionState,
                    emulator = terminalEmulator,
                    cellWidthPx = measuredSize?.cellWidthPx ?: 0,
                    cellHeightPx = measuredSize?.cellHeightPx ?: 0,
                    topRow = topRow,
                    onCopy = { text -> summary.connection.onCopyTextToClipboard(text) },
                    onPaste = { summary.connection.onPasteTextFromClipboard() },
                    onShare = ::shareSelection,
                )
                detectedLink?.let { link ->
                    LinkActionDialog(
                        link = link,
                        onOpen = { openLink(link); detectedLink = null },
                        onCopy = {
                            summary.connection.onCopyTextToClipboard(link.text); detectedLink = null
                        },
                        // The path is known from the tap, so this skips straight to the SAF destination
                        // picker.
                        onDownload = {
                            fileTransferController.requestDownload(
                                summary.connection,
                                link.text
                            ); detectedLink = null
                        },
                        onDismiss = { detectedLink = null },
                    )
                }
            }
            // A sibling of the gesture-bearing Box, not a descendant of it - terminalGestures
            // consumes every down on the Initial pass, which would otherwise swallow the tap.
            if (topRow < 0) {
                JumpToBottomBadge(
                    linesBack = -topRow,
                    onClick = { topRow = 0 },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                )
            }
            if (showLayoutToggle) {
                LayoutToggleButton(
                    fullWidth = fullWidthTerminal,
                    onToggle = onToggleFullWidth,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
        }
        if (preKeyBarGap > 0.dp) Spacer(modifier = Modifier
            .fillMaxWidth()
            .height(preKeyBarGap))
        // `imePadding()` belongs here, on the key bar alone, never on the outer Column. With it on
        // the Column - whose other child is a `weight(1f)` Box - each frame of the IME-hide
        // animation recomputes the remaining-space split from a bottom padding that is itself still
        // animating. If one frame's inset makes the padding read larger than the Column's height,
        // `weight(1f)` clamps the terminal to zero for that frame and the key bar is placed
        // immediately after it, at the top, which reads as a second key bar flashing. Padding the
        // key bar alone removes the weighted sibling from the equation.
        KeyBar(
            ctrlLatched = ctrlLatched,
            altLatched = altLatched,
            onCtrlToggle = { ctrlLatched = !ctrlLatched },
            onAltToggle = { altLatched = !altLatched },
            onSpecialKey = ::sendSpecialKey,
            // Through the same text-input path as typed IME input, so it inherits ::sendText's
            // Ctrl/Alt latch behaviour.
            onMacro = ::sendText,
            keyboardVisible = imeVisible,
            onKeyboardToggle = {
                if (imeVisible) {
                    keyboardController?.hide()
                } else {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
            },
            keys = keyBarKeys,
            rows = keyBarRows,
            modifier = Modifier.imePadding(),
        )
    }

    LaunchedEffect(sessionId) { focusRequester.requestFocus() }
}

/** Scrollback position and jump-to-bottom in one: shown only while scrolled back. */
@Composable
private fun JumpToBottomBadge(linesBack: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        // TalkBack reads the bare glyph as "50 down arrow", which doesn't say what the tap does.
        modifier = modifier.semantics {
            contentDescription = "Scrolled back $linesBack lines. Jump to bottom."
        },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp,
    ) {
        MachineText(
            "$linesBack ↓",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * "Open" is omitted for [LinkType.PATH], which names a path on the remote host rather than anything
 * `Intent.ACTION_VIEW` could open here; "Download" is offered only for it.
 */
@Composable
private fun LinkActionDialog(
    link: TerminalLink,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(link.text) },
        text = { Text(if (link.type == LinkType.PATH) "Path" else "Open in browser or copy?") },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = {
            Row {
                if (link.type == LinkType.PATH) TextButton(onClick = onDownload) { Text("Download") }
                if (link.type != LinkType.PATH) TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
