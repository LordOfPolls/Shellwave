package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.feature.keys.credentialLabel
import io.github.lordofpolls.shellwave.feature.keys.keyDescription

/**
 * The connect-time consent for agent forwarding. Each key is its own tick, pre-ticked, and only the
 * ticked ones are handed on: approving one key never covers another. Dismissing connects without
 * forwarding, so this can delay a connection but never block it.
 */
@Composable
fun AgentForwardingDialog(
    hostName: String,
    keys: List<CredentialEntity>,
    onForward: (List<CredentialEntity>) -> Unit,
    onWithoutForwarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ticked by rememberSaveable(keys.map { it.id }, stateSaver = TickedKeyIdsSaver) {
        mutableStateOf(keys.map { it.id }.toSet())
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onWithoutForwarding,
        title = { Text("Forward keys to $hostName?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "While you're connected, anything on the host running as you, and root, can use " +
                            "the ticked keys.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                keys.forEach { key ->
                    val checked = key.id in ticked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { ticked = if (it) ticked + key.id else ticked - key.id },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(credentialLabel(key), style = MaterialTheme.typography.titleSmall)
                            Text(
                                keyDescription(key, forwarded = true),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = keys.any { it.id in ticked },
                onClick = { onForward(keys.filter { it.id in ticked }) },
            ) { Text("Forward") }
        },
        dismissButton = { TextButton(onClick = onWithoutForwarding) { Text("Connect without forwarding") } },
    )
}
