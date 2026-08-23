package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.feature.home.QuickConnectTarget
import io.github.lordofpolls.shellwave.ui.design.MachineText

/**
 * Quick connect's password prompt. Nothing is written to `hosts` on this path, so it offers the one
 * auth method that suits a fast unsaved connection.
 *
 * The checkbox reads "Save host and password" because both have to happen for an unsaved target: a
 * credential belongs to a host row - and it starts off every time. One that stayed ticked would
 * turn "save this one" into "save everything I type".
 *
 * The saving itself is the caller's, and only once the connection authenticates; saving on tap
 * would persist wrong passwords that then fail silently later. See `MainActivity`'s
 * `pendingCredentialSave`.
 *
 * Keyboard-interactive prompts do not come through here and must not gain a save option: those are
 * usually one-time codes, so saving one stores a dead credential and teaches users to save 2FA
 * answers.
 */
@Composable
fun QuickConnectPasswordDialog(
    target: QuickConnectTarget,
    onCancel: () -> Unit,
    onConnect: (password: String, save: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    // Not `rememberSaveable`, and not hoisted: this must not survive between prompts, and the
    // composable's lifetime is exactly one prompt.
    var save by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        title = { Text("Connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MachineText(
                    "${target.username}@${target.host}:${target.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    // Without a declared content type a password manager sees an anonymous field
                    // and offers nothing to fill.
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Password },
                )
                Row(
                    // The whole row toggles rather than the box alone: a bare Checkbox is a ~20dp
                    // visual target with the only text explaining it sitting outside the hit area,
                    // which is both a miss-prone tap and a TalkBack node with no label attached to
                    // it. `Modifier.toggleable` with `Role.Checkbox` merges the label into one
                    // checkbox node - the M3 idiom - so the whole row is the control and the
                    // Checkbox itself becomes a purely visual indicator (`onCheckedChange = null`,
                    // which stops it registering a second, competing click action).
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = save,
                                role = Role.Checkbox,
                                onValueChange = { save = it },
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = save, onCheckedChange = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Save host and password")
                        Text(
                            "Adds this to your saved hosts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConnect(password, save) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
