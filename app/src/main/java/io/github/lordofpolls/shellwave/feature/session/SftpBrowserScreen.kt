package io.github.lordofpolls.shellwave.feature.session

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.lordofpolls.shellwave.core.util.posixQuote
import io.github.lordofpolls.shellwave.ssh.CaptureResult
import io.github.lordofpolls.shellwave.ssh.RemoteEntry
import io.github.lordofpolls.shellwave.ssh.SftpOp
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ssh.describeSftpFailure
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    onEdit: (RemoteEntry) -> Unit,
    onOpenInTerminal: (String) -> Unit,
    modifier: Modifier = Modifier,
    startPath: String = ".",
    onPathChange: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val fileTransferController = rememberFileTransferController()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current

    var path by remember { mutableStateOf(startPath) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var topMenuOpen by remember { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Pair<DestinationAction, List<RemoteEntry>>?>(null) }
    var compressing by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
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
        onPathChange(path)
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

    fun copyPath(value: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", value))) }
    }

    val entriesByPath = remember(entries) { entries.associateBy { it.path } }
    val selectedEntries = selected.mapNotNull { entriesByPath[it] }

    suspend fun splitByConflict(
        targets: List<RemoteEntry>,
        destination: String,
    ): Pair<List<RemoteEntry>, List<String>>? {
        val free = mutableListOf<RemoteEntry>()
        val conflicts = mutableListOf<String>()
        for (entry in targets) {
            val target = remoteChildPath(destination, entry.name)
            val exists = connection.remoteFileExists(target).getOrElse { e ->
                opError =
                    "Couldn't check whether \"$target\" already exists on the server: " +
                        "${describeSftpFailure(e, SftpOp.Stat, target)}. Nothing was changed."
                return null
            }
            if (exists) conflicts += entry.name else free += entry
        }
        return free to conflicts
    }

    fun runBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    fun withSelection(block: (List<RemoteEntry>) -> Unit) {
        val snapshot = selectedEntries
        if (snapshot.isEmpty()) opError = "Nothing is selected." else block(snapshot)
    }

    fun moveEntries(targets: List<RemoteEntry>, destination: String) {
        runBusy {
            val (free, conflicts) = splitByConflict(targets, destination) ?: return@runBusy
            val failures = free.mapNotNull { entry ->
                connection.renameRemote(entry.path, remoteChildPath(destination, entry.name))
                    .exceptionOrNull()
                    ?.let { "${entry.name} (${describeSftpFailure(it, SftpOp.Rename, entry.path).removeSuffix(".")})" }
            }
            reload()
            opError = batchReport(failures.takeIf { it.isNotEmpty() }?.let { "Couldn't move: ${it.joinToString()}" }, conflicts)
        }
    }

    fun copyEntries(targets: List<RemoteEntry>, destination: String) {
        runBusy {
            val (free, conflicts) = splitByConflict(targets, destination) ?: return@runBusy
            val failure = if (free.isEmpty()) null else
                captureFailure("Copy", connection.execCapture(cpCommand(free.map { it.path }, destination)))
            reload()
            opError = batchReport(failure, conflicts)
        }
    }

    fun compressEntries(targets: List<RemoteEntry>, archiveName: String) {
        val archivePath = remoteChildPath(path, archiveName)
        runBusy {
            val exists = connection.remoteFileExists(archivePath).getOrElse { e ->
                opError =
                    "Couldn't check whether \"$archivePath\" already exists on the server: " +
                        "${describeSftpFailure(e, SftpOp.Stat, archivePath)}. Nothing was compressed."
                return@runBusy
            }
            if (exists) {
                opError = "\"$archiveName\" already exists here. Pick another name."
                return@runBusy
            }
            val failure = captureFailure(
                "Compress",
                connection.execCapture(tarCommand(archivePath, path, targets.map { it.name })),
            )
            reload()
            opError = failure
        }
    }

    fun downloadEntries(targets: List<RemoteEntry>) {
        val files = targets.filter { !it.isDirectory }
        val folders = targets.filter { it.isDirectory }.map { it.name }
        when {
            files.size == 1 && folders.isEmpty() ->
                fileTransferController.requestDownload(connection, files.single().path)

            files.isEmpty() -> opError = batchDownloadSummary(0, emptyList(), folders)
            else -> fileTransferController.requestBatchDownload(connection, files.map { it.path }, folders)
        }
    }

    fun openWith(entry: RemoteEntry) {
        val leaf = safeLocalName(entry.name)
        if (leaf == null) {
            opError = "\"${entry.name}\" has a name that can't be safely saved on this device."
            return
        }
        if (fileTransferController.progress.value != null) {
            opError = "Wait for the current transfer to finish first."
            return
        }
        scope.launch {
            val dir = File(context.cacheDir, "open-with")
            val file = File(dir, leaf)
            val ready = withContext(Dispatchers.IO) {
                dir.deleteRecursively()
                dir.mkdirs()
                file.canonicalFile.parentFile == dir.canonicalFile
            }
            if (!ready) {
                opError = "\"${entry.name}\" has a name that can't be safely saved on this device."
                return@launch
            }
            fileTransferController.startDownload(connection, entry.path, Uri.fromFile(file)) {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val view = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mimeTypeFor(leaf) ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(view, null))
                } catch (e: ActivityNotFoundException) {
                    opError = "No app on this device can open \"${entry.name}\"."
                } catch (e: IllegalArgumentException) {
                    opError = "Couldn't share \"${entry.name}\" with another app."
                }
            }
        }
    }

    fun openInTerminal(target: String) {
        if (cdCommand(target) == null) {
            opError = "This path contains control characters, so it can't be typed into the terminal safely."
        } else {
            onOpenInTerminal(target)
        }
    }

    fun navigate(to: String) {
        history = historyAfterNavigate(history, path, to)
        path = to
    }

    fun stepBack(): Boolean {
        if (selected.isNotEmpty()) {
            selected = emptySet()
            return true
        }
        val (previous, rest) = popHistory(history) ?: return false
        history = rest
        path = previous
        return true
    }

    BackHandler(enabled = selected.isNotEmpty() || history.isNotEmpty()) { stepBack() }

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

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (selected.isNotEmpty()) Text("${selected.size} selected")
                else Breadcrumb(path = path, onNavigate = ::navigate)
            },
            navigationIcon = {
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .combinedClickable(
                            onClick = { if (!stepBack()) onBack() },
                            onLongClick = onBack,
                            onLongClickLabel = "Close browser",
                            role = Role.Button,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected.isNotEmpty()) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = if (selected.isNotEmpty()) "Cancel selection" else "Back",
                    )
                }
            },
            actions = {
                if (selected.isNotEmpty()) {
                    IconButton(onClick = { downloadEntries(selectedEntries) }) {
                        Icon(Icons.Outlined.Download, contentDescription = "Download selected")
                    }
                    IconButton(onClick = { deletingSelection = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete selected")
                    }
                    Box {
                        IconButton(onClick = { selectionMenuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = selectionMenuOpen, onDismissRequest = { selectionMenuOpen = false }) {
                            val single = selectedEntries.singleOrNull()
                            if (single != null) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                    onClick = {
                                        selectionMenuOpen = false
                                        renaming = single
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Move") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                                enabled = !busy,
                                onClick = {
                                    selectionMenuOpen = false
                                    withSelection { picking = DestinationAction.Move to it }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                enabled = !busy,
                                onClick = {
                                    selectionMenuOpen = false
                                    withSelection { picking = DestinationAction.Copy to it }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Compress") },
                                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                                enabled = !busy,
                                onClick = {
                                    selectionMenuOpen = false
                                    withSelection { compressing = it }
                                },
                            )
                            if (single != null && !single.isDirectory) {
                                DropdownMenuItem(
                                    text = { Text("Open with") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        selectionMenuOpen = false
                                        openWith(single)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    remoteParentPath(path)?.let { parent ->
                        TextButton(onClick = { navigate(parent) }) { Text("Up") }
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
                    Box {
                        IconButton(onClick = { topMenuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy path") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                onClick = {
                                    topMenuOpen = false
                                    copyPath(path)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open in terminal") },
                                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                                onClick = {
                                    topMenuOpen = false
                                    openInTerminal(path)
                                },
                            )
                        }
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        if (refreshing || busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

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

                                    entry.isDirectory -> navigate(entry.path)
                                    else -> onEdit(entry)
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
                            onEdit = {
                                menuFor = null
                                onEdit(entry)
                            },
                            onDownload = {
                                menuFor = null
                                fileTransferController.requestDownload(connection, entry.path)
                            },
                            onCopyPath = {
                                menuFor = null
                                copyPath(entry.path)
                            },
                            onOpenWith = {
                                menuFor = null
                                openWith(entry)
                            },
                            onOpenInTerminal = {
                                menuFor = null
                                openInTerminal(entry.path)
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
        val targets = selectedEntries
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

    picking?.let { (action, targets) ->
        RemotePathPickerDialog(
            connection = connection,
            mode = RemotePickerMode.DIRECTORY,
            startPath = path,
            onPick = { destination, isDirectory ->
                if (isDirectory) {
                    picking = null
                    if (action == DestinationAction.Move) moveEntries(targets, destination)
                    else copyEntries(targets, destination)
                }
            },
            onDismiss = { picking = null },
        )
    }

    compressing?.let { targets ->
        NameDialog(
            title = "Compress",
            initialName = defaultArchiveName(targets.map { it.name }),
            confirmLabel = "Compress",
            onConfirm = { name ->
                compressing = null
                compressEntries(targets, name)
            },
            onDismiss = { compressing = null },
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
    onEdit: () -> Unit,
    onDownload: () -> Unit,
    onCopyPath: () -> Unit,
    onOpenWith: () -> Unit,
    onOpenInTerminal: () -> Unit,
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
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = onEdit,
                        )
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
                        DropdownMenuItem(
                            text = { Text("Open with") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                            onClick = onOpenWith,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Copy path") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = onCopyPath,
                    )
                    if (entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Open in terminal") },
                            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                            onClick = onOpenInTerminal,
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

internal fun historyAfterNavigate(history: List<String>, current: String, to: String): List<String> =
    if (to == current) history else history + current

internal fun popHistory(history: List<String>): Pair<String, List<String>>? =
    history.lastOrNull()?.let { it to history.dropLast(1) }

internal fun cdCommand(path: String): String? =
    if (path.any(Char::isISOControl)) null else "cd -- ${posixQuote(path)}\r"

internal enum class DestinationAction { Move, Copy }

internal fun cpCommand(sources: List<String>, destination: String): String =
    "cp -R -- ${sources.joinToString(" ") { posixQuote(it) }} ${posixQuote(destination)}"

internal fun tarCommand(archivePath: String, directory: String, names: List<String>): String =
    "tar -czf ${posixQuote(archivePath)} -C ${posixQuote(directory)} ${names.joinToString(" ") { posixQuote("./$it") }}"

internal fun defaultArchiveName(names: List<String>): String =
    if (names.size == 1) "${names.single()}.tar.gz" else "archive.tar.gz"

internal fun captureFailure(what: String, result: CaptureResult): String? {
    val error = result.error
    return when {
        error != null && error.startsWith("Timed out") ->
            "$what timed out after waiting for the server. It may still be running there."

        error != null -> "$what failed: $error"
        result.exitStatus != 0 ->
            "$what failed (exit ${result.exitStatus ?: "unknown"})" +
                result.stderr.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()

        else -> null
    }
}

internal fun batchReport(failure: String?, conflicts: List<String>): String? =
    listOfNotNull(
        failure,
        conflicts.takeIf { it.isNotEmpty() }?.let { "Skipped, already in the destination: ${it.joinToString()}" },
    ).joinToString("\n").ifEmpty { null }

internal fun safeLocalName(name: String): String? =
    name.substringAfterLast('/').takeIf { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) }
