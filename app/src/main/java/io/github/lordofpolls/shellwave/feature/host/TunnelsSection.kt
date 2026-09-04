package io.github.lordofpolls.shellwave.feature.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardType
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.TunnelState
import io.github.lordofpolls.shellwave.ssh.TunnelStatus
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.RadioRow
import kotlinx.coroutines.launch

/**
 * Port forward CRUD plus live start/stop for one saved host. [PortForwardType.DYNAMIC] needs no
 * destination host or port - each proxied connection's target comes from its own SOCKS5 request.
 *
 * One inline editor below its row, same convention as KeyBarLayoutsScreen - a second pushed screen
 * would be more machinery than a handful of fields needs.
 *
 * Manual start/stop only works against an already-[SessionStatus.CONNECTED] session for this host
 * (the first one found, if more than one happens to be open): a forward is session-scoped state
 * (see SessionManager's class doc), so with no connected session there is nothing to attach to yet;
 * auto-start (also [SessionManager]) covers "comes up by itself on connect" for forwards with
 * [PortForwardEntity.autoStart] set, independent of whether this screen is even open.
 */
@Composable
fun TunnelsSection(
    host: HostEntity,
    portForwardDao: PortForwardDao,
    sessionManager: SessionManager,
    modifier: Modifier = Modifier
) {
    val forwards by portForwardDao.observeForHost(host.id).collectAsState(initial = emptyList())
    val summaries by sessionManager.summaries.collectAsState()
    val activeSession =
        summaries.firstOrNull { it.hostId == host.id && it.status == SessionStatus.CONNECTED }
    val scope = rememberCoroutineScope()

    // null = no editor open; 0L = a not-yet-inserted draft; otherwise the id of the row being
    // edited below itself.
    var editingId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (activeSession == null) {
                "Connect to start/stop forwards live - \"auto-start\" forwards come up on every connect."
            } else {
                "Saved forwards for this host."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        forwards.forEach { forward ->
            val status = activeSession?.tunnels?.firstOrNull { it.forwardId == forward.id }
            TunnelRow(
                forward = forward,
                statusText = status.describe(activeSession != null),
                isError = status?.state == TunnelState.FAILED,
                canControl = activeSession != null,
                isRunning = status?.state == TunnelState.RUNNING,
                onStart = {
                    scope.launch {
                        activeSession?.let {
                            sessionManager.startForward(
                                it.id,
                                forward
                            )
                        }
                    }
                },
                onStop = {
                    scope.launch {
                        activeSession?.let {
                            sessionManager.stopForward(
                                it.id,
                                forward.id
                            )
                        }
                    }
                },
                onEdit = { editingId = if (editingId == forward.id) null else forward.id },
                onDelete = { scope.launch { portForwardDao.delete(forward) } },
            )
            if (editingId == forward.id) {
                TunnelEditor(
                    existing = forward,
                    hostId = host.id,
                    onSave = { updated ->
                        scope.launch {
                            portForwardDao.update(updated); editingId = null
                        }
                    },
                    onCancel = { editingId = null },
                )
            }
        }

        TextButton(onClick = {
            editingId = if (editingId == 0L) null else 0L
        }) { Text("+ Add forward") }
        if (editingId == 0L) {
            TunnelEditor(
                existing = null,
                hostId = host.id,
                onSave = { new -> scope.launch { portForwardDao.insert(new); editingId = null } },
                onCancel = { editingId = null },
            )
        }
    }
}

private fun TunnelStatus?.describe(sessionConnected: Boolean): String {
    val status = this
    return when {
        status == null && !sessionConnected -> "Not connected"
        status == null -> "Not started"
        status.state == TunnelState.RUNNING -> "Running"
        status.state == TunnelState.FAILED -> "Failed: ${status.error ?: "unknown error"}"
        else -> "Stopped"
    }
}

@Composable
private fun TunnelRow(
    forward: PortForwardEntity,
    statusText: String,
    isError: Boolean,
    canControl: Boolean,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val type =
        runCatching { PortForwardType.valueOf(forward.type) }.getOrDefault(PortForwardType.LOCAL)
    val summary =
        when (type) {
            PortForwardType.LOCAL -> "Local :${forward.bindPort} -> ${forward.targetHost}:${forward.targetPort} (via server)"
            PortForwardType.REMOTE -> "Remote server:${forward.bindPort} -> ${forward.targetHost}:${forward.targetPort} (this device)"
            PortForwardType.DYNAMIC -> "Dynamic SOCKS5 proxy :${forward.bindPort} (via server)"
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Machine-asserted ports and hosts, plus a short "(via server)"/"(this device)" suffix
            // that is this screen's own prose. The whole line goes through MachineText instead of
            // splitting the suffix out: it is a parenthetical clarifying which side a bare port
            // binds on, and breaking the line in two would read worse.
            MachineText(summary, style = MaterialTheme.typography.bodyMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (forward.autoStart) Text(
                    "· auto-start",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canControl) {
                    if (isRunning) {
                        TextButton(onClick = onStop) { Text("Stop") }
                    } else {
                        TextButton(onClick = onStart) { Text("Start") }
                    }
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/**
 * Bind address is a checkbox in place of a free-text field: unchecked stores `null`, which
 * LOOPBACK_ADDRESS resolves at start time, and checked stores the literal `"0.0.0.0"` - a
 * deliberate, visible choice with its consequence spelled out beside it, never something a blank
 * field slides into by accident.
 */
@Composable
private fun TunnelEditor(
    existing: PortForwardEntity?,
    hostId: Long,
    onSave: (PortForwardEntity) -> Unit,
    onCancel: () -> Unit
) {
    var type by remember(existing?.id) {
        mutableStateOf(
            runCatching { PortForwardType.valueOf(existing?.type ?: "") }.getOrDefault(
                PortForwardType.LOCAL
            )
        )
    }
    var bindPort by remember(existing?.id) {
        mutableStateOf(
            existing?.bindPort?.toString().orEmpty()
        )
    }
    var exposeToNetwork by remember(existing?.id) { mutableStateOf(existing?.bindAddress == "0.0.0.0") }
    var targetHost by remember(existing?.id) { mutableStateOf(existing?.targetHost.orEmpty()) }
    var targetPort by remember(existing?.id) {
        mutableStateOf(
            existing?.targetPort?.toString().orEmpty()
        )
    }
    var autoStart by remember(existing?.id) { mutableStateOf(existing?.autoStart ?: false) }

    // DYNAMIC has no fixed destination, so it alone is saveable without target host/port.
    fun canSave(): Boolean =
        bindPort.toIntOrNull() != null &&
                (type == PortForwardType.DYNAMIC || (targetHost.isNotBlank() && targetPort.toIntOrNull() != null))

    // A SOCKS5 listener is a local listener too, so LOCAL and DYNAMIC share the bind-port label and
    // the "expose to network" checkbox. Only REMOTE binds on the server and not here.
    val bindsLocally = type == PortForwardType.LOCAL || type == PortForwardType.DYNAMIC

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (existing == null) "New forward" else "Edit forward",
                style = MaterialTheme.typography.titleSmall
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioRow(
                    selected = type == PortForwardType.LOCAL,
                    onClick = { type = PortForwardType.LOCAL },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Local")
                }
                RadioRow(
                    selected = type == PortForwardType.REMOTE,
                    onClick = { type = PortForwardType.REMOTE },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Remote")
                }
                RadioRow(
                    selected = type == PortForwardType.DYNAMIC,
                    onClick = { type = PortForwardType.DYNAMIC },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Dynamic (SOCKS5)")
                }
            }
            Text(
                when (type) {
                    PortForwardType.LOCAL -> "Listens on this device; the server relays each connection to the destination."
                    PortForwardType.REMOTE -> "Listens on the server; connections are relayed to a destination reachable from this device."
                    PortForwardType.DYNAMIC -> "This device becomes a SOCKS5 proxy; the server makes each connection and resolves hostnames remotely."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = bindPort,
                onValueChange = { bindPort = it },
                label = { Text(if (bindsLocally) "Local port" else "Port on the server") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (bindsLocally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = exposeToNetwork, onCheckedChange = { exposeToNetwork = it })
                    Text("Allow other devices on this network to use it (binds 0.0.0.0)")
                }
                if (exposeToNetwork) {
                    Text(
                        if (type == PortForwardType.DYNAMIC) {
                            "Anyone on this network can tunnel arbitrary traffic through this connection - an open proxy."
                        } else {
                            "Anyone on this network can reach this forward, not just this device."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (type != PortForwardType.DYNAMIC) {
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it },
                    label = { Text(if (type == PortForwardType.LOCAL) "Destination host (from the server)" else "Destination host (from this device)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = targetPort,
                    onValueChange = { targetPort = it },
                    label = { Text("Destination port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoStart, onCheckedChange = { autoStart = it })
                Text("Start automatically when this host connects")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(
                    enabled = canSave(),
                    onClick = {
                        onSave(
                            PortForwardEntity(
                                id = existing?.id ?: 0,
                                hostId = hostId,
                                type = type.name,
                                bindAddress = if (bindsLocally && exposeToNetwork) "0.0.0.0" else null,
                                bindPort = bindPort.toIntOrNull() ?: return@TextButton,
                                targetHost = if (type == PortForwardType.DYNAMIC) null else targetHost,
                                targetPort = if (type == PortForwardType.DYNAMIC) null else targetPort.toIntOrNull(),
                                autoStart = autoStart,
                            ),
                        )
                    },
                ) { Text("Save") }
            }
        }
    }
}
