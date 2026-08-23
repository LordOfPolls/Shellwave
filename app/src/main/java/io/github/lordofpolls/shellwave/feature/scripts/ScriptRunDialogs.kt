package io.github.lordofpolls.shellwave.feature.scripts

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.ui.design.MachineText

/**
 * Rendered once, high in the composition, so a run started from any screen still shows its dialogs
 * whichever screen is on top when they resolve.
 */
@Composable
fun ScriptRunDialogs(
    controller: ScriptRunController,
    hosts: List<HostEntity>,
    modifier: Modifier = Modifier
) {
    controller.pendingHostChoice?.let { script ->
        ChooseHostDialog(
            script = script,
            hosts = hosts,
            onChoose = { host -> controller.chooseHost(script, host.id) },
            onDismiss = controller::dismiss,
        )
    }

    controller.pending?.let { script ->
        val params = remember(script.id) { decodeParams(script.paramsJson) }
        if (params.isEmpty()) {
            if (script.confirmBeforeRun) {
                ConfirmRunDialog(
                    script = script,
                    onConfirm = { controller.confirmRun(script, emptyMap()) },
                    onDismiss = controller::dismiss
                )
            } else {
                // No params and no confirmation required - the tap that opened this already was the
                // confirmation.
                controller.confirmRun(script, emptyMap())
            }
        } else {
            RunParamsDialog(
                script = script,
                params = params,
                onRun = { values -> controller.confirmRun(script, values) },
                onDismiss = controller::dismiss
            )
        }
    }

    controller.error?.let { message ->
        AlertDialog(
            onDismissRequest = controller::clearError,
            title = { Text("Couldn't run script") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::clearError) { Text("OK") } },
        )
    }

    controller.captureUiState?.let { state ->
        CaptureResultDialog(
            state = state,
            onDismiss = controller::dismissCapture
        )
    }
}

/**
 * "Ask each run", answered. One row per saved host, tapped to pick: a radio list plus a Run button
 * would put two taps where the choice is the confirmation.
 *
 * Confirm-before-run and `{{param}}` prompting still happen afterwards, because this step feeds the
 * ordinary flow rather than bypassing it.
 */
@Composable
private fun ChooseHostDialog(
    script: ScriptEntity,
    hosts: List<HostEntity>,
    onChoose: (HostEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run \"${script.name}\" on") },
        text = {
            if (hosts.isEmpty()) {
                Text("No saved hosts yet. Add one and this script will be able to run against it.")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    hosts.forEach { host ->
                        // Same label/hostname split as everywhere else: a user-chosen label is
                        // human text, a bare hostname is a machine assertion.
                        val label = host.label
                        TextButton(
                            onClick = { onChoose(host) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (label != null) Text(label) else MachineText(host.hostname)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmRunDialog(script: ScriptEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run \"${script.name}\"?") },
        text = { Text("This runs on ${modeDescription(script.mode)}.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Run") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun modeDescription(mode: String): String =
    when (runCatching { ScriptMode.valueOf(mode) }.getOrNull()) {
        ScriptMode.ATTACH -> "the target host, staying attached"
        ScriptMode.CAPTURE -> "the target host, capturing output"
        ScriptMode.SEND_TO_CURRENT -> "the current session"
        null -> "the target host"
    }

@Composable
private fun RunParamsDialog(
    script: ScriptEntity,
    params: List<ScriptParam>,
    onRun: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val values = remember(script.id) {
        mutableStateMapOf<String, String>().apply {
            params.forEach { p ->
                if (p.type == ParamType.CHOICE) put(
                    p.name,
                    p.choices.firstOrNull().orEmpty()
                )
            }
        }
    }
    val allFilled = params.all { !values[it.name].isNullOrEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run \"${script.name}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                params.forEach { param ->
                    ParamField(
                        param = param,
                        value = values[param.name].orEmpty(),
                        onValueChange = { values[param.name] = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRun(values.toMap()) },
                enabled = allFilled
            ) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ParamField(param: ScriptParam, value: String, onValueChange: (String) -> Unit) {
    when (param.type) {
        ParamType.CHOICE ->
            Column {
                Text(param.label, style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    param.choices.forEach { choice ->
                        FilterChip(
                            selected = value == choice,
                            onClick = { onValueChange(choice) },
                            label = { Text(choice) })
                    }
                }
            }

        ParamType.SECRET ->
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(param.label) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

        ParamType.TEXT ->
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(param.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
    }
}

@Composable
private fun CaptureResultDialog(state: CaptureRunUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.scriptName) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.running) {
                    Text("Running…")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // The words are the app's, the value is the machine's.
                        Text("Exit status:", style = MaterialTheme.typography.labelLarge)
                        MachineText(
                            state.run?.exitStatus?.toString() ?: "unknown",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                    state.run?.stdout?.takeIf { it.isNotEmpty() }?.let { out ->
                        Text("stdout", style = MaterialTheme.typography.labelMedium)
                        // MachineText over a bare FontFamily.Monospace, which is a different mono
                        // face from the rest of the chrome's.
                        MachineText(out, style = MaterialTheme.typography.bodySmall)
                    }
                    state.run?.stderr?.takeIf { it.isNotEmpty() }?.let { err ->
                        Text("stderr", style = MaterialTheme.typography.labelMedium)
                        MachineText(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (!state.running && state.run != null) {
                Row {
                    TextButton(onClick = {
                        val clipboard =
                            context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "script run",
                                resultText(state)
                            )
                        )
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, resultText(state))
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }) { Text("Share") }
                }
            }
        },
    )
}

private fun resultText(state: CaptureRunUiState): String {
    val run = state.run ?: return ""
    return buildString {
        appendLine("Exit status: ${run.exitStatus ?: "unknown"}")
        if (!run.stdout.isNullOrEmpty()) {
            appendLine("--- stdout ---")
            appendLine(run.stdout)
        }
        if (!run.stderr.isNullOrEmpty()) {
            appendLine("--- stderr ---")
            appendLine(run.stderr)
        }
    }
}
