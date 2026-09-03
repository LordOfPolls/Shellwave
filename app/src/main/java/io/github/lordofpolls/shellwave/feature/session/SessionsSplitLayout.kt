package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.dao.KeyBarLayoutDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.ui.FoldPosture
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.SessionSummary
import io.github.lordofpolls.shellwave.ui.design.SessionCard

/**
 * The terminal area's own layout switch: tabletop's vertical split, a bypassed full-width pane, or
 * the ordinary [ListDetailPaneScaffold]. The floating [LayoutToggleButton] is rendered inside
 * `SessionTabBody`'s terminal box rather than here, so it never sits over the search bar above it.
 * Pulled out of `SessionsScreen` itself so that composable stays about assembling the screen's
 * chrome, not about choosing between these three.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun SessionsSplitLayout(
    posture: FoldPosture,
    isWide: Boolean,
    fullWidthTerminal: Boolean,
    onToggleFullWidth: () -> Unit,
    navigator: ThreePaneScaffoldNavigator<Long>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    summaries: List<SessionSummary>,
    hosts: List<HostEntity>,
    sessionManager: SessionManager,
    terminalProfile: TerminalProfileEntity?,
    terminalProfileDao: TerminalProfileDao,
    keyBarLayoutDao: KeyBarLayoutDao,
    fileTransferController: FileTransferController,
    searchController: TerminalSearchController,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val tabletop = posture as? FoldPosture.Tabletop
        if (tabletop != null) {
            // A vertical split ListDetailPaneScaffold has no concept of, so this bypasses it.
            TabletopSessionLayout(
                hingeBoundsPx = tabletop.hingeBoundsPx,
                sessionId = selectedId,
                sessionManager = sessionManager,
                terminalProfile = terminalProfile,
                terminalProfileDao = terminalProfileDao,
                keyBarLayoutDao = keyBarLayoutDao,
                fileTransferController = fileTransferController,
                searchController = searchController,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (isWide && fullWidthTerminal) {
            // Bypasses the scaffold rather than talking it into a Detail-only pane while
            // `isWide` stays true. Reachable only while `isWide`, so a narrow window cannot get
            // stuck.
            SessionTabBody(
                sessionId = selectedId,
                sessionManager = sessionManager,
                terminalProfile = terminalProfile,
                terminalProfileDao = terminalProfileDao,
                keyBarLayoutDao = keyBarLayoutDao,
                fileTransferController = fileTransferController,
                searchController = searchController,
                showLayoutToggle = true,
                fullWidthTerminal = fullWidthTerminal,
                onToggleFullWidth = onToggleFullWidth,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            ListDetailPaneScaffold(
                directive = navigator.scaffoldDirective,
                scaffoldState = navigator.scaffoldState,
                listPane = {
                    AnimatedPane {
                        SessionListPane(
                            summaries = summaries,
                            hosts = hosts,
                            selectedId = selectedId,
                            onSelect = onSelect,
                            onReconnect = sessionManager::reconnectNow,
                            onClose = sessionManager::closeSession,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                detailPane = {
                    AnimatedPane {
                        SessionTabBody(
                            sessionId = selectedId,
                            sessionManager = sessionManager,
                            terminalProfile = terminalProfile,
                            terminalProfileDao = terminalProfileDao,
                            keyBarLayoutDao = keyBarLayoutDao,
                            fileTransferController = fileTransferController,
                            searchController = searchController,
                            showLayoutToggle = isWide,
                            fullWidthTerminal = fullWidthTerminal,
                            onToggleFullWidth = onToggleFullWidth,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                },
            )
        }
    }
}

/**
 * The full-width/split toggle, and the one control still floating over the terminal. Folding it
 * into the rail's overflow would put a layout control for the screen inside a menu about the
 * session. Renders only where there are two panes to choose between.
 */
@Composable
internal fun LayoutToggleButton(
    fullWidth: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onToggle,
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 4.dp,
    ) {
        Text(
            if (fullWidth) "◧ Split view" else "▭ Full width",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The list pane, hidden on a narrow window where the chip rail is the only switcher. Width-based
 * collapse is [androidx.compose.material3.adaptive.layout.PaneScaffoldDirective]'s call, not this
 * file's.
 *
 * Each row is a [SessionCard], the same component the Sessions destination uses, so there is one
 * anatomy to keep correct. [onReconnect]/[onClose] are per-card, so an unselected
 * `FAILED`/`DISCONNECTED` session needn't be switched to first just to reconnect or close it.
 */
@Composable
private fun SessionListPane(
    summaries: List<SessionSummary>,
    hosts: List<HostEntity>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onReconnect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        summaries.forEach { summary ->
            SessionCard(
                name = sessionDisplayName(summary.hostId, summary.label, hosts),
                summary = summary,
                selected = summary.id == selectedId,
                onClick = { onSelect(summary.id) },
                onReconnect = { onReconnect(summary.id) },
                onClose = { onClose(summary.id) },
            )
        }
    }
}

/**
 * Stops the terminal at the hinge's top edge and pushes the key bar below its bottom edge, instead
 * of the ordinary `weight(1f)` split. [hingeBoundsPx] is in window coordinates and this composable
 * is not necessarily at the window's top edge, so it measures its own position with
 * [onGloballyPositioned] and subtracts before using them.
 */
@Composable
private fun TabletopSessionLayout(
    hingeBoundsPx: Rect,
    sessionId: Long,
    sessionManager: SessionManager,
    terminalProfile: TerminalProfileEntity?,
    terminalProfileDao: TerminalProfileDao,
    keyBarLayoutDao: KeyBarLayoutDao,
    fileTransferController: FileTransferController,
    searchController: TerminalSearchController,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var originInWindowPx by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier.onGloballyPositioned { coords ->
        originInWindowPx = coords.positionInWindow()
    }) {
        val localHingeTopPx = (hingeBoundsPx.top - originInWindowPx.y).coerceAtLeast(0f)
        val localHingeBottomPx =
            (hingeBoundsPx.bottom - originInWindowPx.y).coerceAtLeast(localHingeTopPx)
        val terminalHeight = with(density) { localHingeTopPx.toDp() }
        val hingeGap = with(density) { (localHingeBottomPx - localHingeTopPx).toDp() }

        if (terminalHeight > 0.dp) {
            SessionTabBody(
                sessionId = sessionId,
                sessionManager = sessionManager,
                terminalProfile = terminalProfile,
                terminalProfileDao = terminalProfileDao,
                keyBarLayoutDao = keyBarLayoutDao,
                fileTransferController = fileTransferController,
                searchController = searchController,
                terminalHeight = terminalHeight,
                preKeyBarGap = hingeGap,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Hinge at or above this layout's own top edge, e.g. mid fold-transition before the
            // first onGloballyPositioned lands. Fall back to the weighted split rather than hand
            // SessionTabBody a zero or negative height.
            SessionTabBody(
                sessionId = sessionId,
                sessionManager = sessionManager,
                terminalProfile = terminalProfile,
                terminalProfileDao = terminalProfileDao,
                keyBarLayoutDao = keyBarLayoutDao,
                fileTransferController = fileTransferController,
                searchController = searchController,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
