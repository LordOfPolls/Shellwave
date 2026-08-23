package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SessionSummary

/**
 * The Hosts screen's route back to a running session: one 40dp line showing either the session's
 * `user@host:port` or, with several live, `N SESSIONS`.
 *
 * [sessions] is filtered here rather than by the caller, so the rule lives with the component: a
 * CLOSED session is not something to return to, and a strip appearing for one would claim a session
 * is waiting. Those stay reachable from Sessions, which has the error text and Reconnect.
 */
@Composable
fun LiveSessionStrip(
    sessions: List<SessionSummary>,
    onOpenSession: (Long) -> Unit,
    onOpenSessionList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = sessions.filter { it.status == SessionStatus.CONNECTED }
    if (live.isEmpty()) return

    val single = live.singleOrNull()
    Surface(
        onClick = { if (single != null) onOpenSession(single.id) else onOpenSessionList() },
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusMarker(SessionStatus.CONNECTED)
            MachineText(
                text = single?.label ?: "${live.size} SESSIONS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
