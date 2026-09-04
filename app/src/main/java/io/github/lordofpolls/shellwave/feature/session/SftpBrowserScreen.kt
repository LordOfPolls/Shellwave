package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.RemoteEntry
import io.github.lordofpolls.shellwave.ssh.SftpOp
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ssh.describeSftpFailure
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The full SFTP browser, distinct from [RemotePathPickerDialog]: this one mutates the remote
 * filesystem, so it is a pushed screen with its own top bar rather than a dialog over a text
 * field. Its own [FileTransferController] rather than the terminal's: this screen is reachable
 * only after a session's overflow menu hands off an already-authenticated [connection], and
 * nothing here needs to share transfer state with the terminal underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpBrowserScreen(
    connection: SshConnection,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startPath: String = ".",
) {
    val scope = rememberCoroutineScope()
    val fileTransferController = rememberFileTransferController()

    var path by remember { mutableStateOf(startPath) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameOverwriteConfirm by remember { mutableStateOf<Pair<RemoteEntry, String>?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }
    var deletingSelection by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<String?>(null) }

    suspend fun fetchListing() {
        val requested = path
        failure = null
        selected = emptySet()
        connection.listRemoteDirectory(requested).fold(
            onSuccess = { listing ->
                // A directory change in flight makes this listing stale; the path effect
                // already started a fresh request for wherever the user navigated to.
                if (requested != path) return@fold
                path = listing.path
                entries = sortedForPicker(listing.entries)
            },
            onFailure = { e ->
                if (requested != path) return@fold
                entries = emptyList()
                failure = describeSftpFailure(e, SftpOp.List, requested)
            },
        )
    }

    LaunchedEffect(path) {
        // Not the suspending scrollToItem: the LazyColumn isn't composed yet while the
        // full-screen spinner is up (it's only in the `else` branch below), so scrollToItem
        // would await a scroll mutex that never gets attached and hang forever.
        listState.requestScrollToItem(0)
        loading = true
        fetchListing()
        loading = false
    }

    LaunchedEffect(reloadToken) {
        if (reloadToken == 0) return@LaunchedEffect
        refreshing = true
        fetchListing()
        refreshing = false
    }

    fun reload() {
        reloadToken++
    }

    fun renameEntry(entry: RemoteEntry, target: String) {
        scope.launch {
            connection.renameRemote(entry.path, target).fold(
                onSuccess = { reload() },
                onFailure = { e ->
                    opError = "Couldn't rename \"${entry.name}\": ${describeSftpFailure(e, SftpOp.Rename, entry.path)}"
                },
            )
        }
    }

    // A completed transfer (success or failure) may have changed what this directory holds -
    // an upload adds a file, and either way the progress dialog closing is the only signal this
    // screen gets that the copy is done.
    val transferResult = fileTransferController.result
    LaunchedEffect(transferResult) {
        if (transferResult != null) reload()
    }

    val entriesByPath = remember(entries) { entries.associateBy { it.path } }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (selected.isNotEmpty()) Text("${selected.size} selected")
                else Breadcrumb(path = path, onNavigate = { path = it })
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (selected.isNotEmpty()) selected = emptySet() else onBack()
                }) {
                    Icon(
                        if (selected.isNotEmpty()) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = if (selected.isNotEmpty()) "Cancel selection" else "Back",
                    )
                }
            },
            actions = {
                if (selected.isNotEmpty()) {
                    IconButton(onClick = { deletingSelection = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete selected")
                    }
                } else {
                    remoteParentPath(path)?.let { parent ->
                        TextButton(onClick = { path = parent }) { Text("Up") }
                    }
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { creatingFolder = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                    }
                    IconButton(onClick = {
                        fileTransferController.requestUpload(connection, initialDirectory = path)
                    }) {
                        Icon(Icons.Outlined.Upload, contentDescription = "Upload")
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        if (refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            failure != null ->
                EmptyState(
                    message = "Couldn't list \"$path\": $failure",
                    modifier = Modifier.fillMaxSize(),
                )

            entries.isEmpty() && !refreshing ->
                EmptyState(message = "Empty directory", modifier = Modifier.fillMaxSize())

            else ->
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    items(entries, key = { it.path }) { entry ->
                        SftpEntryRow(
                            entry = entry,
                            selected = entry.path in selected,
                            selectionMode = selected.isNotEmpty(),
                            onClick = {
                                when {
                                    selected.isNotEmpty() ->
                                        selected =
                                            if (entry.path in selected) selected - entry.path else selected + entry.path

                                    entry.isDirectory -> path = entry.path
                                }
                            },
                            onLongClick = { selected = selected + entry.path },
                            onOverflow = { menuFor = entry.path },
                            menuExpanded = menuFor == entry.path,
                            onDismissMenu = { menuFor = null },
                            onRename = {
                                menuFor = null
                                renaming = entry
                            },
                            onDelete = {
                                menuFor = null
                                deleting = entry
                            },
                            onDownload = {
                                menuFor = null
                                fileTransferController.requestDownload(connection, entry.path)
                            },
                        )
                    }
                }
        }
    }

    renaming?.let { entry ->
        NameDialog(
            initialName = entry.name,
            onConfirm = { newName ->
                renaming = null
                val target = remoteChildPath(remoteParentPath(entry.path) ?: "/", newName)
                if (target != entry.path) {
                    scope.launch {
                        // Never guess: an unchecked rename could silently clobber an existing
                        // file the same way an unchecked upload could.
                        connection.remoteFileExists(target).fold(
                            onSuccess = { exists ->
                                if (exists) renameOverwriteConfirm = entry to target
                                else renameEntry(entry, target)
                            },
                            onFailure = { e ->
                                opError =
                                    "Couldn't check whether \"$target\" already exists on the server: " +
                                        "${describeSftpFailure(e, SftpOp.Stat, target)}. Nothing was renamed."
                            },
                        )
                    }
                }
            },
            onDismiss = { renaming = null },
        )
    }

    renameOverwriteConfirm?.let { (entry, target) ->
        AlertDialog(
            onDismissRequest = { renameOverwriteConfirm = null },
            title = { Text("Overwrite \"$target\"?") },
            text = { Text("A file already exists at that path on the server. Replace it?") },
            confirmButton = {
                TextButton(onClick = {
                    renameOverwriteConfirm = null
                    renameEntry(entry, target)
                }) { Text("Overwrite") }
            },
            dismissButton = { TextButton(onClick = { renameOverwriteConfirm = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${entry.name}\"?") },
            text = { Text(if (entry.isDirectory) "The folder must be empty." else "This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        val result =
                            if (entry.isDirectory) connection.removeRemoteDir(entry.path) else connection.removeRemoteFile(
                                entry.path
                            )
                        result.fold(
                            onSuccess = { reload() },
                            onFailure = { e ->
                                val op = if (entry.isDirectory) SftpOp.DeleteDir else SftpOp.Delete
                                opError = "Couldn't delete \"${entry.name}\": ${describeSftpFailure(e, op, entry.path)}"
                            },
                        )
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (deletingSelection) {
        val targets = selected.mapNotNull { entriesByPath[it] }
        AlertDialog(
            onDismissRequest = { deletingSelection = false },
            title = { Text("Delete ${targets.size} items?") },
            text = { Text("Non-empty folders are skipped rather than emptied for you.") },
            confirmButton = {
                TextButton(onClick = {
                    deletingSelection = false
                    scope.launch {
                        val failures = targets.mapNotNull { entry ->
                            val result =
                                if (entry.isDirectory) connection.removeRemoteDir(entry.path) else connection.removeRemoteFile(
                                    entry.path
                                )
                            result.exceptionOrNull()?.let { entry to it }
                        }
                        reload()
                        if (failures.isNotEmpty()) {
                            opError =
                                "Couldn't delete: ${
                                    failures.joinToString { (entry, e) ->
                                        val op = if (entry.isDirectory) SftpOp.DeleteDir else SftpOp.Delete
                                        val reason = describeSftpFailure(e, op, entry.path).removeSuffix(".")
                                        "${entry.name} ($reason)"
                                    }
                                }"
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingSelection = false }) { Text("Cancel") } },
        )
    }

    if (creatingFolder) {
        NameDialog(
            title = "New folder",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                creatingFolder = false
                val target = remoteChildPath(path, name)
                scope.launch {
                    connection.makeRemoteDir(target).fold(
                        onSuccess = { reload() },
                        onFailure = { e ->
                            opError = "Couldn't create \"$name\": ${describeSftpFailure(e, SftpOp.MakeDir, target)}"
                        },
                    )
                }
            },
            onDismiss = { creatingFolder = false },
        )
    }

    opError?.let { message ->
        AlertDialog(
            onDismissRequest = { opError = null },
            title = { Text("Couldn't complete that") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { opError = null }) { Text("OK") } },
        )
    }

    FileTransferDialogs(controller = fileTransferController)
}

@Composable
private fun NameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Rename",
    confirmLabel: String = "Rename",
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Long-press selects rather than opens a menu of its own: the per-row overflow already covers
 * every single-item action, so a second gesture to reach the same actions would be redundant.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SftpEntryRow(
    entry: RemoteEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOverflow: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = "${if (entry.isDirectory) "Folder" else "File"} ${entry.name}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
            )
        } else {
            Icon(
                if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            MachineText(entry.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            entryDetail(entry)?.let {
                MachineText(it, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!selectionMode) {
            Box {
                IconButton(onClick = onOverflow) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${entry.name}")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DriveFileRenameOutline,
                                contentDescription = null
                            )
                        },
                        onClick = onRename,
                    )
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Download") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Download,
                                    contentDescription = null
                                )
                            },
                            onClick = onDownload,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

/**
 * A directory's size is meaningless over SFTP (it reports the directory entry's own size, not its
 * contents), so only a file's row grows one. [RemoteEntry.mtime] is seconds since epoch; `Date`
 * wants milliseconds.
 */
internal fun entryDetail(entry: RemoteEntry): String? {
    val sizePart =
        if (!entry.isDirectory && entry.size >= 0) formatByteCount(entry.size) else null
    val timePart =
        if (entry.mtime >= 0) {
            DateFormat.getDateInstance().format(Date(entry.mtime * 1000))
        } else {
            null
        }
    return listOfNotNull(sizePart, timePart).joinToString(" · ").ifBlank { null }
}
