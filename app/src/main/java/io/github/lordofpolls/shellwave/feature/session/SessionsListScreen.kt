package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.ssh.SessionSummary
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.SessionCard

/**
 * Selecting Sessions once dropped straight into a terminal, so there was no view of what was
 * running and a session that had failed in the background could only be found by switching to it.
 * The destination is now the overview, and the terminal is pushed on top of whichever destination
 * opened it, which lets back return there.
 *
 * A list, never thumbnails: they cost renderer work per session and show nothing legible at that
 * size. Actions are Reconnect and Close; file transfer lives in the terminal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    summaries: List<SessionSummary>,
    hosts: List<HostEntity>,
    onOpenSession: (Long) -> Unit,
    onReconnect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onOpenHosts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        // MainActivity's Scaffold already applied the system bar insets; defaults would apply them
        // twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
    ) { innerPadding ->
        if (summaries.isEmpty()) {
            EmptyState(
                message = "No open sessions.",
                actionLabel = "Open Hosts",
                onAction = onOpenHosts,
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
                items(summaries, key = { it.id }) { summary ->
                    SessionCard(
                        name = sessionDisplayName(summary.hostId, summary.label, hosts),
                        summary = summary,
                        // Selection is a wide-window concept; the overview has no such property.
                        selected = false,
                        onClick = { onOpenSession(summary.id) },
                        onReconnect = { onReconnect(summary.id) },
                        onClose = { onClose(summary.id) },
                    )
                }
            }
        }
    }
}

/**
 * A quick-connect session has no saved host, and a session outliving a deleted host row has an id
 * that no longer resolves. Both fall back to the host portion of [identity], not the whole string,
 * since row 2 already shows the full identity.
 *
 * Takes [hostId]/[identity] and not a [SessionSummary], which owns a live connection, so this stays
 * JVM-testable.
 */
fun sessionDisplayName(hostId: Long?, identity: String, hosts: List<HostEntity>): String {
    val host = hostId?.let { id -> hosts.firstOrNull { it.id == id } }
    if (host != null) return host.label ?: host.hostname
    return identity.substringAfter('@').substringBeforeLast(':').ifEmpty { identity }
}
