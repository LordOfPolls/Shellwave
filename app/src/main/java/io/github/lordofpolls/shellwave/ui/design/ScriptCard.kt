package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ToggleOn
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

/**
 * Run takes the leading edge as the frequent action; overflow trails; tapping the body edits, the
 * editor being a script's detail view.
 *
 * The `WIDGET`/`QS TILE` badges are words rather than pictographs: registration is machine truth
 * and belongs beside the mode and target. [onPinToWidget]/[onUseForQsTile] are nullable because a
 * background trigger refuses anything but a capture script, and offering to pin one would be a
 * promise the app will not keep.
 */
@Composable
fun ScriptCard(
    name: String,
    modeAndTarget: String,
    pinnedToWidget: Boolean,
    isQsTile: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onHistory: (() -> Unit)? = null,
    onPinToWidget: (() -> Unit)? = null,
    onUseForQsTile: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            // Tighter than the other cards: the two IconButtons each bring their own 48dp box.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Naming the script here keeps a column of identical play triangles from reading to
            // TalkBack as several identical "Run" buttons.
            IconButton(onClick = onRun) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "Run $name")
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Edit") { onEdit() }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MachineText(
                        modeAndTarget,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (pinnedToWidget) TriggerBadge("WIDGET")
                    if (isQsTile) TriggerBadge("QS TILE")
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options for $name")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Run") },
                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                        onClick = { menuExpanded = false; onRun() },
                    )
                    if (onHistory != null) {
                        DropdownMenuItem(
                            text = { Text("History") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.History,
                                    contentDescription = null
                                )
                            },
                            onClick = { menuExpanded = false; onHistory() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    if (onPinToWidget != null) {
                        DropdownMenuItem(
                            // The label says what tapping does; the badge on row 2 reports state.
                            text = { Text(if (pinnedToWidget) "Unpin from widget" else "Pin to widget") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.PushPin,
                                    contentDescription = null
                                )
                            },
                            onClick = { menuExpanded = false; onPinToWidget() },
                        )
                    }
                    if (onUseForQsTile != null) {
                        DropdownMenuItem(
                            text = { Text(if (isQsTile) "Remove from QS tile" else "Use for QS tile") },
                            // `Toggles` is absent from the installed material-icons-extended
                            // artifact.
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.ToggleOn,
                                    contentDescription = null
                                )
                            },
                            onClick = { menuExpanded = false; onUseForQsTile() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** No container or tint: a chip would make these look like controls when they state facts. */
@Composable
private fun TriggerBadge(token: String) {
    MachineText(
        token,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
