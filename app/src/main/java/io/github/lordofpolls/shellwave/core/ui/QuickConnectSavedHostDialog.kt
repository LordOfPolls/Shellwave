package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.feature.home.QuickConnectTarget
import io.github.lordofpolls.shellwave.ui.design.MachineText

/**
 * Offers a saved host's credential when what was typed into quick connect turns out to be something
 * already saved. Without it, typing `dave@betty` for a host saved as BETTY asks for a password
 * already in the vault, opens a session with `hostId = null`, drops that host's resilient-session,
 * proxy and profile settings, and never updates its last-connected time.
 *
 * It asks rather than decides. Quick connect is the escape hatch for connecting to something as
 * typed, and a saved host may carry a jump chain or a biometric credential the user did not want
 * this time, so "Enter password" is always there and does what quick connect always did.
 *
 * Several hosts can match one address, so that case lists them instead of picking one.
 */
@Composable
fun QuickConnectSavedHostDialog(
    target: QuickConnectTarget,
    matches: List<HostEntity>,
    onUseSaved: (HostEntity) -> Unit,
    onEnterPassword: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val single = matches.singleOrNull()

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        title = { Text(if (single != null) "Use saved host?" else "Several saved hosts match") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MachineText(
                    "${target.username}@${target.host}:${target.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (single != null) {
                    Text("${hostDisplayName(single)} is already saved. Connect with its saved settings?")
                } else {
                    Text("Pick one to connect with, or enter a password to connect as typed.")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        matches.forEachIndexed { index, host ->
                            if (index > 0) HorizontalDivider()
                            Text(
                                hostDisplayName(host),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        // A list row is the only way to pick a host here, so it has
                                        // to clear the 48dp target minimum on its own: the text
                                        // alone is ~20dp. Same idiom as SettingsScreen's selectable
                                        // rows.
                                        .minimumInteractiveComponentSize()
                                        .clickable(role = Role.Button) { onUseSaved(host) }
                                        .padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (single != null) TextButton(onClick = { onUseSaved(single) }) { Text("Use saved") }
        },
        dismissButton = { TextButton(onClick = onEnterPassword) { Text("Enter password") } },
    )
}

/** A saved host's own name if it has one, else the address it points at - never a blank row. */
private fun hostDisplayName(host: HostEntity): String =
    host.label?.takeIf { it.isNotBlank() } ?: "${host.username}@${host.hostname}:${host.port}"
