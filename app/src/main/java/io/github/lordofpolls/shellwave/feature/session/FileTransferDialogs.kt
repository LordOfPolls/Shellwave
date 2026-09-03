package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ui.design.MachineText

/**
 * Renders whatever [controller] needs shown, and owns the two SAF launchers that drive it.
 *
 * Rendered from [SessionsScreen] rather than hoisted to `MainActivity` the way ScriptRunDialogs is:
 * this feature is only reachable from inside an already-open session.
 */
@Composable
internal fun FileTransferDialogs(
    controller: FileTransferController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Path unknown: prompt for it, then hand off to pendingDownload exactly like a tap would.
    controller.downloadPathPrompt?.let { connection ->
        RemotePathPromptDialog(
            title = "Download file",
            label = "Remote path",
            confirmLabel = "Next",
            connection = connection,
            pickerMode = RemotePickerMode.FILE,
            onConfirm = controller::confirmDownloadPath,
            onDismiss = controller::dismissDownloadPathPrompt,
        )
    }

    // ACTION_CREATE_DOCUMENT does not prompt "replace this file?" when an existing name is chosen;
    // it returns the existing document's Uri silently, so this checks the returned Uri for content
    // itself before the controller decides whether to write or confirm.
    val pendingDownload = controller.pendingDownload
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            controller.downloadDestinationPicked(
                uri,
                alreadyHasContent = uri != null && existingUriHasContent(context, uri)
            )
        }
    LaunchedEffect(pendingDownload) {
        if (pendingDownload != null) {
            val suggestedName =
                pendingDownload.remotePath.substringAfterLast('/').ifBlank { "download" }
            createDocument.launch(suggestedName)
        }
    }

    // "Upload file" (explicit action): pick a local source first...
    val uploadSourcePrompt = controller.uploadSourcePrompt
    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            controller.uploadSourcePicked(uri, uri?.let { displayNameForUpload(context, it) } ?: "")
        }
    LaunchedEffect(uploadSourcePrompt) {
        if (uploadSourcePrompt != null) openDocument.launch(arrayOf("*/*"))
    }

    // ...then name the remote destination.
    controller.uploadDestinationPrompt?.let { pending ->
        RemotePathPromptDialog(
            title = "Upload \"${pending.sourceName}\"",
            label = "Remote destination path",
            confirmLabel = "Upload",
            initialValue = pending.initialDirectory?.let { remoteChildPath(it, pending.sourceName) }
                ?: pending.sourceName,
            connection = pending.connection,
            pickerMode = RemotePickerMode.DIRECTORY,
            onConfirm = controller::confirmUploadDestination,
            onDismiss = controller::dismissUploadDestinationPrompt,
        )
    }

    // The remote side has no prompt of its own. Declining leaves the remote file untouched: nothing
    // is written before this point.
    controller.overwriteConfirm?.let { request ->
        AlertDialog(
            onDismissRequest = controller::dismissOverwrite,
            title = { Text("Overwrite remote file?") },
            text = { Text("\"${request.remotePath}\" already exists on the server. Replace it?") },
            confirmButton = { TextButton(onClick = controller::confirmOverwrite) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = controller::dismissOverwrite) { Text("Cancel") } },
        )
    }

    // The local gate. Declining discards pendingDownload without ever opening the Uri for writing,
    // so the existing file is left exactly as it was.
    controller.localOverwriteConfirm?.let {
        AlertDialog(
            onDismissRequest = controller::dismissLocalOverwrite,
            title = { Text("Overwrite local file?") },
            text = { Text("This file already has content. Replace it?") },
            confirmButton = { TextButton(onClick = controller::confirmLocalOverwrite) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = controller::dismissLocalOverwrite) { Text("Cancel") } },
        )
    }

    val progress by controller.progress.collectAsState()
    progress?.let { p ->
        AlertDialog(
            onDismissRequest = {}, // Cancel is the only way out while a transfer is running
            title = { Text(p.label) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Transferring…")
                    // Without tabular figures the digits change width as the count ticks up and the
                    // line jitters.
                    MachineText(
                        if (p.totalBytes != null && p.totalBytes > 0) {
                            "${formatByteCount(p.bytesTransferred)} / ${formatByteCount(p.totalBytes)}"
                        } else {
                            formatByteCount(p.bytesTransferred)
                        },
                    )
                }
            },
            confirmButton = { TextButton(onClick = controller::cancelActiveTransfer) { Text("Cancel") } },
        )
    }

    controller.result?.let { result ->
        AlertDialog(
            onDismissRequest = controller::dismissResult,
            title = { Text(result.label) },
            text = {
                Text(
                    result.message,
                    color = if (result.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = { TextButton(onClick = controller::dismissResult) { Text("OK") } },
        )
    }

    controller.error?.let { message ->
        AlertDialog(
            onDismissRequest = controller::clearError,
            title = { Text("Couldn't transfer file") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::clearError) { Text("OK") } },
        )
    }
}

/**
 * Typing a remote path is the primary control here, no fallback. RemotePathPickerDialog opens over
 * this one and writes its answer back into this field, so the user always confirms a path they can
 * see and edit, and a server that cannot be browsed costs them nothing but a cancelled dialog.
 *
 * A picked directory is joined to whatever filename the field already holds ([remoteChildPath]),
 * which is what makes "Use this folder" work during an upload - the source filename is already
 * sitting there. A picked file replaces the field outright.
 */
@Composable
private fun RemotePathPromptDialog(
    title: String,
    label: String,
    confirmLabel: String,
    initialValue: String = "",
    connection: SshConnection,
    pickerMode: RemotePickerMode,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(initialValue) }
    var browsing by remember { mutableStateOf(false) }

    if (browsing) {
        RemotePathPickerDialog(
            connection = connection,
            mode = pickerMode,
            startPath = startingDirectoryFor(path),
            onPick = { picked, isDirectory ->
                path = if (isDirectory) remoteChildPath(
                    picked,
                    path.substringAfterLast('/')
                ) else picked
                browsing = false
            },
            onDismiss = { browsing = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = { browsing = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Browse the server")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(path) },
                enabled = path.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun displayNameForUpload(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

/**
 * Queried, not opened for writing: the download path only truncates on its own later
 * `getOutputStream("w")` call, so the original content is still intact when this reads
 * [OpenableColumns.SIZE].
 *
 * A provider that doesn't report a size is treated as "no existing content" - erring the other way
 * would block every legitimately-new file a picky provider happens not to size-report.
 */
private fun existingUriHasContent(context: Context, uri: Uri): Boolean =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > 0
            }
    }.getOrNull() ?: false
