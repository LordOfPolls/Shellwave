package io.github.lordofpolls.shellwave.feature.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ui.design.MachineText

/**
 * An [AlertDialog] instead of a bottom sheet, to match every other dialog in this feature - this
 * app has no [androidx.compose.material3.ModalBottomSheet] anywhere, and one template picker is not
 * the reason to introduce a second dialog idiom.
 *
 * Picking a template saves nothing: [onPick] routes to [ScriptEditorScreen]'s `prefill`, so the
 * user lands in the editor with the fields filled and nothing reaches the database until they save.
 * So the snippet is shown here - the catalogue is browsed to find something to *start from*, and
 * the snippet tells you whether this is the one.
 */
@Composable
fun ScriptTemplateDialog(onPick: (ScriptTemplate) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start from a template") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ScriptTemplates.ALL.forEach { template ->
                    // A whole-row target instead of a TextButton: each row is three lines tall, and
                    // a button sized to that much content reads as a block of chrome rather than a
                    // choice. Role.Button keeps it announced as one to TalkBack.
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClick = { onPick(template) })
                                .padding(vertical = 8.dp),
                    ) {
                        Text(template.name, style = MaterialTheme.typography.titleSmall)
                        Text(template.description, style = MaterialTheme.typography.bodySmall)
                        // A command line, reproduced exactly, so it goes through MachineText.
                        MachineText(
                            template.snippet,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
