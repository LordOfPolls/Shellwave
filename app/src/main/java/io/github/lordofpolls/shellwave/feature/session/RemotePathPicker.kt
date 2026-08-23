package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.RemoteEntry
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ui.design.MachineText

/**
 * In `FILE` a directory is only ever somewhere to walk through; in [DIRECTORY] the directory the
 * user is standing in is itself a valid answer, so only that mode has a confirm button.
 */
internal enum class RemotePickerMode {
    FILE,
    DIRECTORY,
}

internal data class RemoteCrumb(val label: String, val path: String)

/**
 * Navigate, select one path, dismiss. No mkdir, rename or delete - a picker, not an SFTP browser.
 *
 * Opened from [RemotePathPromptDialog] rather than instead of it: picking fills the text field and
 * the user still confirms a path they can see, and dismissing lands back on that field.
 *
 * Listing failure is a state rather than a toast. A server with no SFTP subsystem cannot be browsed
 * at all, and an empty list would read as "this directory is empty".
 */
@Composable
internal fun RemotePathPickerDialog(
    connection: SshConnection,
    mode: RemotePickerMode,
    startPath: String,
    onPick: (path: String, isDirectory: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(startPath) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Keyed on the path, so every navigation re-lists. A failed listing leaves `path` where it was,
    // so the breadcrumb never claims the user is somewhere the server refused to show them.
    LaunchedEffect(path) {
        loading = true
        failure = null
        connection.listRemoteDirectory(path).fold(
            onSuccess = { listing ->
                path = listing.path
                entries = sortedForPicker(listing.entries)
                loading = false
            },
            onFailure = { e ->
                entries = emptyList()
                failure = e.message ?: "The server did not return a listing."
                loading = false
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == RemotePickerMode.DIRECTORY) "Choose a folder" else "Choose a file") },
        text = {
            Column {
                Breadcrumb(path = path, onNavigate = { path = it })
                when {
                    loading -> Row(modifier = Modifier.padding(vertical = 16.dp)) { CircularProgressIndicator() }
                    failure != null ->
                        Text(
                            "Couldn't list \"$path\": $failure\n\nNo SFTP subsystem? Cancel and type the path instead.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )

                    else ->
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            remoteParentPath(path)?.let { parent ->
                                // The breadcrumb scrolls out of reach on a deep path, and going up
                                // must never be what scrolled away.
                                item {
                                    EntryRow(
                                        label = "..",
                                        isDirectory = true,
                                        description = "Up one level",
                                        onClick = { path = parent })
                                }
                            }
                            if (entries.isEmpty()) {
                                item {
                                    Text(
                                        "Empty directory",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                }
                            }
                            items(entries, key = { it.path }) { entry ->
                                EntryRow(
                                    label = entry.name,
                                    isDirectory = entry.isDirectory,
                                    description = if (entry.isDirectory) "Folder ${entry.name}" else "File ${entry.name}",
                                    onClick = {
                                        // In DIRECTORY mode, tapping a file is how the user
                                        // replaces that exact file; the overwrite confirmation
                                        // still gates it.
                                        if (entry.isDirectory) path =
                                            entry.path else onPick(entry.path, false)
                                    },
                                )
                            }
                        }
                }
            }
        },
        confirmButton = {
            if (mode == RemotePickerMode.DIRECTORY) {
                TextButton(
                    onClick = { onPick(path, true) },
                    enabled = failure == null && !loading
                ) { Text("Use this folder") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Buttons, since they are the only way back up more than one level in a single tap. */
@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        remoteBreadcrumbs(path).forEach { crumb ->
            TextButton(
                onClick = { onNavigate(crumb.path) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                MachineText(crumb.label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * [minimumInteractiveComponentSize] instead of a hand-set height: these rows are only as tall as
 * the text in them, and a filename can be one character. One semantics node for the row, because a
 * screen reader reading "Folder" and then "logs" makes the type sound like a separate item.
 */
@Composable
private fun EntryRow(
    label: String,
    isDirectory: Boolean,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .minimumInteractiveComponentSize()
                .padding(horizontal = 4.dp)
                .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null
        )
        MachineText(label, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Directories first, then case-insensitive by name. Dotfiles are not hidden: `~/.ssh/config` is
 * exactly the kind of path this picker exists to reach.
 */
internal fun sortedForPicker(entries: List<RemoteEntry>): List<RemoteEntry> =
    entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

/**
 * Trailing slashes are trimmed first, so `/var/log/` and `/var/log` climb to the same place. A path
 * with no separator left has no knowable parent, and guessing `.` would walk somewhere the user
 * never asked for.
 */
internal fun remoteParentPath(path: String): String? {
    val trimmed = path.trimEnd('/').ifEmpty { return null }
    if (!trimmed.contains('/')) return null
    return trimmed.substringBeforeLast('/').ifEmpty { "/" }
}

internal fun remoteBreadcrumbs(path: String): List<RemoteCrumb> {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return listOf(RemoteCrumb("/", "/"))
    val absolute = trimmed.startsWith('/')
    val crumbs = mutableListOf<RemoteCrumb>()
    if (absolute) crumbs += RemoteCrumb("/", "/")
    var accumulated = ""
    trimmed.split('/').filter { it.isNotEmpty() }.forEach { segment ->
        accumulated =
            if (accumulated.isEmpty()) (if (absolute) "/$segment" else segment) else "$accumulated/$segment"
        crumbs += RemoteCrumb(segment, accumulated)
    }
    return crumbs
}

internal fun remoteChildPath(directory: String, name: String): String {
    val base = directory.trimEnd('/')
    return if (base.isEmpty()) "/$name" else "$base/$name"
}

/**
 * Opening at home when the user has already typed `/var/log/syslog` throws away the only hint
 * available about where they meant to be, and a picker that lands back at `~` every time is what
 * makes browsing feel worse than typing.
 */
internal fun startingDirectoryFor(typedPath: String): String {
    val trimmed = typedPath.trim()
    if (trimmed.isEmpty() || !trimmed.contains('/')) return "."
    if (trimmed.endsWith('/')) return trimmed.trimEnd('/').ifEmpty { "/" }
    return trimmed.substringBeforeLast('/').ifEmpty { "/" }
}
