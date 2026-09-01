package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode

/**
 * "Run script here". Both modes that can act on an open session are offered, so each row carries a
 * second line saying which one it is. The difference is invisible from the script's name and is not
 * cosmetic: a send-to-current script is typed into the live shell, inheriting the working directory
 * and everything else the prompt is sitting in, and scrolls past in the terminal; a capture script
 * gets its own `exec` channel on the same connection, starting fresh at `~` with no shell state,
 * and comes back in a result sheet that is kept in run history. Picking the wrong one is only
 * obvious afterwards, so the label goes on the row.
 */
@Composable
internal fun ScriptPickerDialog(
    scripts: List<ScriptEntity>,
    onPick: (ScriptEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run a script here") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                scripts.forEach { script ->
                    TextButton(onClick = { onPick(script) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(script.name)
                            Text(
                                if (runCatching { ScriptMode.valueOf(script.mode) }.getOrNull() == ScriptMode.CAPTURE) {
                                    "Runs separately, output captured"
                                } else {
                                    "Typed into this session"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
