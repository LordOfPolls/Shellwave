package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.HostVerificationRequest
import io.github.lordofpolls.shellwave.ui.design.FingerprintBlock
import io.github.lordofpolls.shellwave.ui.design.MachineText

private const val MISMATCH_CONFIRM_PHRASE = "OVERRIDE"

/**
 * The TOFU prompt and the mismatch block: an unknown host gets a plain first-contact confirmation,
 * but a changed host key gets a hard block whose override is awkward on purpose - typing
 * [MISMATCH_CONFIRM_PHRASE] literally, not a button sitting next to Cancel - so nobody clicks
 * through a MITM warning by reflex. The awkwardness is the point; do not smooth it out.
 *
 * The copy stays short: show the key, say what happens next, ask for a decision. It does not try to
 * teach SSH's trust model inline.
 */
@Composable
fun HostVerificationDialog(request: HostVerificationRequest, modifier: Modifier = Modifier) {
    if (request.isMismatch) {
        MismatchDialog(request, modifier)
    } else {
        TofuDialog(request, modifier)
    }
}

/**
 * First contact with a host. Not an error, and the copy does not read like one: this is the normal
 * state of every host the first time, and a user who is taught to fear this dialog learns to click
 * through the other one too.
 */
@Composable
private fun TofuDialog(request: HostVerificationRequest, modifier: Modifier = Modifier) {
    AlertDialog(
        modifier = modifier,
        // A tap outside a trust prompt must never be a yes.
        onDismissRequest = { request.decide(false) },
        title = { Text("Trust this host key?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MachineText(
                    "${request.hostname}  ${request.keyType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FingerprintBlock(request.fingerprint)
                Text(
                    "First connection to this host. Shellwave will remember this key and warn you if it changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { request.decide(true) }) { Text("Trust and connect") } },
        dismissButton = { TextButton(onClick = { request.decide(false) }) { Text("Cancel") } },
    )
}

/**
 * The host key changed. This is the dialog the whole trust model exists for, and the one whose
 * friction must not be reduced. A reviewer suggesting a plain confirm button here gets the standing
 * answer: no - the awkwardness stops a reflex tap from accepting an interception.
 *
 * The copy leads with what it means and not with the fact, and names the innocent explanation and
 * the dangerous one, because a warning that only cries wolf gets dismissed on the day it is real.
 */
@Composable
private fun MismatchDialog(request: HostVerificationRequest, modifier: Modifier = Modifier) {
    var confirmText by remember { mutableStateOf("") }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = { request.decide(false) },
        title = {
            Text(
                "This server's identity has changed",
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This doesn't match the key Shellwave saw last time - a server rebuild, or " +
                            "someone intercepting the connection.",
                    color = MaterialTheme.colorScheme.error,
                )
                MachineText(
                    "${request.hostname}  ${request.keyType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FingerprintBlock(request.fingerprint)
                Text(
                    "Only continue if you're sure this change is expected. Type $MISMATCH_CONFIRM_PHRASE to proceed:",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { request.decide(true) },
                // An exact match on the typed phrase, nothing looser.
                enabled = confirmText == MISMATCH_CONFIRM_PHRASE,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Connect anyway") }
        },
        dismissButton = { TextButton(onClick = { request.decide(false) }) { Text("Cancel (recommended)") } },
    )
}
