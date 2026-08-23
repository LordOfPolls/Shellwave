package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import java.util.Locale

/** Not SessionSummary: that owns a live connection. */
data class SessionChipModel(val id: Long, val name: String, val status: SessionStatus)

/**
 * The terminal's top strip: tap switches session, long-press closes, a trailing `+` opens Hosts as
 * a picker, and [overflow] stays pinned at the right edge. It is the terminal's only session
 * indicator; it once shared the screen with a status header, so a connecting session said
 * "CONNECTING" twice.
 *
 * Chips carry the square without the word, which a rail of `■ LIVE BETTY` would spend half its
 * width restating. Each chip pays that back with `stateDescription = statusWord(status)` on a
 * `Role.Tab` node, so TalkBack announces "BETTY, tab, selected, LIVE".
 *
 * [Surface] + [combinedClickable] instead of an M3 chip: the private `SelectableChip` appends its
 * own click handling to the passed `modifier`, so a long-press detector on top of it is the
 * nested-clickable shape Compose does not arbitrate, and TalkBack would see two click actions on
 * one node. HostCard has the same shape on a plain `Card`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionChipRail(
    sessions: List<SessionChipModel>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    overflow: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            // Only the chips scroll. Scrolled off, the one control that reaches close, bell and
            // file transfer would be unreachable exactly when many sessions are open.
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sessions.forEach { session ->
                SessionChip(
                    session = session,
                    isSelected = session.id == selectedId,
                    onClick = { onSelect(session.id) },
                    onClose = { onClose(session.id) },
                )
            }
            NewSessionChip(onClick = onNewSession)
        }
        overflow()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionChip(
    session: SessionChipModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val word = statusWord(session.status)
    Surface(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onClose,
                    onClickLabel = "Switch to this session",
                    onLongClickLabel = "Close session",
                    role = Role.Tab,
                )
                .semantics {
                    selected = isSelected
                    // Colour is invisible to TalkBack, so without this the chip announces a
                    // session's name and selection state but never its condition.
                    stateDescription = word
                },
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusSquare(session.status)
            MachineText(
                session.name.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Shaped like the chips beside it, since it is part of the same row of switchable things: it just
 * switches to a session that doesn't exist yet.
 */
@Composable
private fun NewSessionChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = "New session" },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
        }
    }
}
