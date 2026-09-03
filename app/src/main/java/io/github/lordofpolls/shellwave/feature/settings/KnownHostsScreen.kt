package io.github.lordofpolls.shellwave.feature.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.KnownHostRow
import io.github.lordofpolls.shellwave.ssh.listKnownHosts
import io.github.lordofpolls.shellwave.ssh.removeKnownHost
import io.github.lordofpolls.shellwave.ui.design.BackTopBar
import io.github.lordofpolls.shellwave.ui.design.FingerprintBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "KnownHostsScreen"

/**
 * Lists and removes trusted host keys from `known_hosts`. There is no add or edit here - the only
 * way a key gets trusted is the TOFU gate, which this screen does not touch.
 */
@Composable
fun KnownHostsScreen(
    knownHostsFile: File,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<KnownHostRow>>(emptyList()) }
    var pendingRemoval by remember { mutableStateOf<KnownHostRow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        withContext(Dispatchers.IO) { runCatching { listKnownHosts(knownHostsFile) } }
            .onSuccess { rows = it; error = null }
            .onFailure {
                Log.w(TAG, "failed to list known_hosts", it)
                error = "Couldn't read the trusted host keys."
            }
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = "Trusted host keys", onBack = onBack)

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }

        if (rows.isEmpty() && error == null) {
            Text(
                "No trusted host keys yet - connecting to a new host adds one here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { row ->
                    KnownHostRowCard(row = row, onRemove = { pendingRemoval = row })
                }
            }
        }
    }

    pendingRemoval?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Forget this key?") },
            text = { Text("The next connection will prompt to trust it again.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { removeKnownHost(knownHostsFile, row.line) } }
                            .onSuccess { removed ->
                                error = if (removed) null else "That entry is already gone."
                                reload()
                            }
                            .onFailure {
                                Log.w(TAG, "failed to remove known_hosts entry", it)
                                error = "Couldn't remove that key."
                            }
                    }
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun KnownHostRowCard(row: KnownHostRow, onRemove: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(row.hostDisplay, style = MaterialTheme.typography.titleSmall)
                    Text(
                        row.marker?.let { "${row.keyType} · $it" } ?: row.keyType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Forget this key")
                }
            }
            HorizontalDivider()
            FingerprintBlock(fingerprint = row.fingerprint)
        }
    }
}
