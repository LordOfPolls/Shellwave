package io.github.lordofpolls.shellwave.ui.design

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SessionSummary
import kotlinx.coroutines.delay

/**
 * [name] is a parameter and not derived here: `SessionSummary` carries a host id and no host row,
 * and a design-system component should not be querying one. Quick-connect sessions have no saved
 * host at all, so the caller passes the hostname it dialled.
 *
 * The error text shows for FAILED but not RECONNECTING. A red paragraph flickering in and out on
 * each backoff attempt reads as a fault to act on when it is the retry machinery working; once the
 * retries give up it is there in full, unclamped.
 */
@Composable
fun SessionCard(
    name: String,
    summary: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uptime = rememberUptimeText(summary.status, summary.connectedAtElapsedRealtime)

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors =
            CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else CardDefaults.cardColors().containerColor,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusMarker(summary.status)
                if (uptime != null) {
                    // Tabular figures keep a column of uptimes in step as the digits tick over.
                    MachineText(
                        uptime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            MachineText(
                summary.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.status == SessionStatus.FAILED) {
                summary.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (summary.status == SessionStatus.DISCONNECTED || summary.status == SessionStatus.FAILED) {
                    TextButton(onClick = onReconnect) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("Reconnect")
                    }
                }
                TextButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Close")
                }
            }
        }
    }
}

/**
 * `null` whenever the session isn't connected, which is exactly when
 * [SessionSummary.connectedAtElapsedRealtime] is null.
 *
 * The clock's origin deliberately does not live here. A "first observed connected" timestamp in a
 * `remember` would make the displayed uptime the age of the card: every visit to Sessions composes
 * a fresh one, so leaving and coming back would restart the count at `0s` on a connection up for
 * hours. SessionManager owns the origin, and its lifetime is the session's.
 */
@Composable
private fun rememberUptimeText(status: SessionStatus, connectedAt: Long?): String? {
    if (status != SessionStatus.CONNECTED || connectedAt == null) return null

    var nowMillis by remember(connectedAt) { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            nowMillis = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    return formatUptime(nowMillis - connectedAt)
}

/** Coarsens to the largest non-zero unit; seconds stop mattering after a few hours. */
fun formatUptime(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm".format(hours, minutes)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}
