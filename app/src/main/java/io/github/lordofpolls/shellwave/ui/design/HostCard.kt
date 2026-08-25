package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.net.Reachability
import io.github.lordofpolls.shellwave.ssh.SessionStatus

/** `ui/design` takes the fields it renders, never a persistence type. */
data class HostScriptAction(val id: Long, val name: String, val onRun: () -> Unit)

/**
 * Name and status on row 1, `user@host:port` on row 2, nothing else in or under the card. Cards
 * were once twice as tall as their content, with each host's scripts as free-floating boxes
 * underneath, so a handful of hosts filled the screen. Natural height is now ~68dp.
 *
 * StatusMarker appears inline only while a session for this host is live or transitioning;
 * otherwise a non-null [reachability] puts a [ReachabilityMarker] in the same slot, never both.
 *
 * The overflow button exists because long-press alone is not discoverable. Its script submenu is a
 * second page of the same menu, since M3 Compose has no cascading-submenu component.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HostCard(
    displayName: String,
    identity: String,
    liveStatus: SessionStatus?,
    reachability: Reachability?,
    scripts: List<HostScriptAction>,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    /** Null hides the Wake item rather than disabling it. */
    onWake: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Reset on every open so the menu never reappears mid-submenu.
    var showingScripts by remember { mutableStateOf(false) }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        showingScripts = false
                        menuExpanded = true
                    },
                    onClickLabel = "Connect",
                    onLongClickLabel = "Host options",
                ),
    ) {
        // The overflow sits outside row 1: an IconButton pads itself to a 48dp touch target, which
        // would make that row 48dp tall on its own and push the card to ~90dp.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // LIVE means this app holds an open connection; UP means something answered on
                    // that port a moment ago. Showing both leaves the user deciding which to
                    // believe.
                    if (liveStatus != null) {
                        StatusMarker(liveStatus)
                    } else if (reachability != null) {
                        ReachabilityMarker(reachability)
                    }
                }
                MachineText(
                    identity,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(
                    onClick = {
                        showingScripts = false
                        menuExpanded = true
                    },
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "More options for $displayName"
                    )
                }
                HostMenu(
                    expanded = menuExpanded,
                    showingScripts = showingScripts,
                    scripts = scripts,
                    onDismiss = { menuExpanded = false },
                    onShowScripts = { showingScripts = true },
                    onHideScripts = { showingScripts = false },
                    onConnect = { menuExpanded = false; onClick() },
                    onEdit = { menuExpanded = false; onEdit() },
                    onDuplicate = { menuExpanded = false; onDuplicate() },
                    onWake = onWake?.let { wake -> { menuExpanded = false; wake() } },
                    onDelete = { menuExpanded = false; onDelete() },
                    onRunScript = { action -> menuExpanded = false; action.onRun() },
                )
            }
        }
    }
}

/** Delete is last and tinted `error`. It reports intent only; the confirmation is the caller's. */
@Composable
private fun HostMenu(
    expanded: Boolean,
    showingScripts: Boolean,
    scripts: List<HostScriptAction>,
    onDismiss: () -> Unit,
    onShowScripts: () -> Unit,
    onHideScripts: () -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onWake: (() -> Unit)?,
    onDelete: () -> Unit,
    onRunScript: (HostScriptAction) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showingScripts) {
            DropdownMenuItem(
                text = { Text("Back") },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null
                    )
                },
                onClick = onHideScripts,
            )
            scripts.forEach { script ->
                DropdownMenuItem(
                    text = { Text(script.name) },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = { onRunScript(script) },
                )
            }
        } else {
            DropdownMenuItem(
                text = { Text("Connect") },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.Login,
                        contentDescription = null
                    )
                },
                onClick = onConnect,
            )
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = onEdit,
            )
            DropdownMenuItem(
                text = { Text("Duplicate") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = onDuplicate,
            )
            if (onWake != null) {
                DropdownMenuItem(
                    text = { Text("Wake") },
                    leadingIcon = {
                        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
                    },
                    onClick = onWake,
                )
            }
            if (scripts.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Run script") },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    trailingIcon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null
                        )
                    },
                    onClick = onShowScripts,
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                },
                onClick = onDelete,
            )
        }
    }
}
