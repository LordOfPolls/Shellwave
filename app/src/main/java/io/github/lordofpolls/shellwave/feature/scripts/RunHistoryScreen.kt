package io.github.lordofpolls.shellwave.feature.scripts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.ScriptRunEntity
import io.github.lordofpolls.shellwave.ui.design.MachineText
import java.text.DateFormat
import java.util.Date

@Composable
fun RunHistoryScreen(
    scriptName: String,
    runs: List<ScriptRunEntity>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<ScriptRunEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< Back") }
            Text("$scriptName - history", style = MaterialTheme.typography.headlineSmall)
        }

        if (runs.isEmpty()) {
            Text("No runs yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(runs, key = { it.id }) { run ->
                    RunHistoryRow(run = run, onClick = { selected = run })
                }
            }
        }
    }

    selected?.let { run -> RunDetailDialog(run = run, onDismiss = { selected = null }) }
}

@Composable
private fun RunHistoryRow(
    run: ScriptRunEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MachineText(formatTime(run.startedAt), style = MaterialTheme.typography.bodyMedium)
            MachineText(exitStatusLabel(run.exitStatus), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RunDetailDialog(run: ScriptRunEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { MachineText(formatTime(run.startedAt)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Exit status:", style = MaterialTheme.typography.labelLarge)
                    MachineText(
                        exitStatusLabel(run.exitStatus),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                run.stdout?.takeIf { it.isNotEmpty() }?.let { out ->
                    Text("stdout", style = MaterialTheme.typography.labelMedium)
                    // FontFamily.Monospace is a different mono face from the rest of the chrome's.
                    MachineText(out, style = MaterialTheme.typography.bodySmall)
                }
                run.stderr?.takeIf { it.isNotEmpty() }?.let { err ->
                    Text("stderr", style = MaterialTheme.typography.labelMedium)
                    MachineText(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun exitStatusLabel(exitStatus: Int?): String = exitStatus?.toString() ?: "unknown"

private fun formatTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(epochMillis))
