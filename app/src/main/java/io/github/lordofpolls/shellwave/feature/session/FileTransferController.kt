package io.github.lordofpolls.shellwave.feature.session

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.lordofpolls.shellwave.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class PendingDownload(val connection: SshConnection, val remotePath: String)

internal data class PendingUpload(
    val connection: SshConnection,
    val source: Uri,
    val sourceName: String,
    // Set when upload was requested from a directory the caller already knows, e.g. the SFTP
    // browser's current folder, so the destination prompt starts there instead of a bare filename.
    val initialDirectory: String? = null,
)

internal data class TransferProgress(
    val label: String,
    val bytesTransferred: Long,
    val totalBytes: Long?
)

internal data class TransferUiResult(val label: String, val success: Boolean, val message: String)

internal data class OverwriteRequest(val pending: PendingUpload, val remotePath: String)

/**
 * A state-holder driving a couple of `AlertDialog`s, the shape ScriptRunController uses. Reached
 * from the explicit Download/Upload actions and from the terminal's path-tap dialog, both passing
 * an already-authenticated SshConnection - there is no second credential path here.
 *
 * Overwrite is confirmed in both directions. `ACTION_CREATE_DOCUMENT` was assumed to ask "replace
 * this file?" itself; it does not: picking an existing filename returns with no system prompt, and
 * this app would have truncated it. [localOverwriteConfirm] covers that, [overwriteConfirm] the
 * remote side, which has no picker to rely on at all.
 *
 * [progress] is a [StateFlow] because `onProgress` fires from the IO thread doing the copy.
 */
internal class FileTransferController internal constructor(private val scope: CoroutineScope) {
    var downloadPathPrompt by mutableStateOf<SshConnection?>(null)
        private set

    /** [FileTransferDialogs] reacts to this becoming non-null by launching the `CreateDocument` picker, then reports back via [downloadDestinationPicked]. */
    var pendingDownload by mutableStateOf<PendingDownload?>(null)
        private set

    var uploadSourcePrompt by mutableStateOf<SshConnection?>(null)
        private set

    var uploadDestinationPrompt by mutableStateOf<PendingUpload?>(null)
        private set

    var overwriteConfirm by mutableStateOf<OverwriteRequest?>(null)
        private set

    /** Set when the `CreateDocument`-picked `Uri` already has content. */
    var localOverwriteConfirm by mutableStateOf<Uri?>(null)
        private set

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress

    var result by mutableStateOf<TransferUiResult?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var activeJob: Job? = null
    private var activeConnection: SshConnection? = null
    private var cancelledByUser = false

    fun requestDownload(connection: SshConnection) {
        downloadPathPrompt = connection
    }

    /** The remote path is already known, so this skips [downloadPathPrompt] and goes straight to the SAF destination picker. */
    fun requestDownload(connection: SshConnection, remotePath: String) {
        pendingDownload = PendingDownload(connection, remotePath)
    }

    fun confirmDownloadPath(path: String) {
        val connection = downloadPathPrompt ?: return
        downloadPathPrompt = null
        if (path.isNotBlank()) pendingDownload = PendingDownload(connection, path.trim())
    }

    fun dismissDownloadPathPrompt() {
        downloadPathPrompt = null
    }

    /**
     * The `CreateDocument` launcher's result. [alreadyHasContent] true routes to
     * [localOverwriteConfirm] rather than starting the download, since `ACTION_CREATE_DOCUMENT` does
     * not prompt before returning an existing file's `Uri`. A null [destination] means the picker was
     * cancelled: nothing written, nothing reported. [pendingDownload] is discarded either way, so a
     * fresh tap starts over instead of re-showing a stale confirmation.
     */
    fun downloadDestinationPicked(destination: Uri?, alreadyHasContent: Boolean = false) {
        if (destination != null && alreadyHasContent) {
            localOverwriteConfirm = destination
            return
        }
        val pending = pendingDownload
        pendingDownload = null
        if (pending == null || destination == null) return
        startDownload(pending.connection, pending.remotePath, destination)
    }

    fun confirmLocalOverwrite() {
        val destination = localOverwriteConfirm ?: return
        localOverwriteConfirm = null
        downloadDestinationPicked(destination, alreadyHasContent = false)
    }

    /** Declining leaves the file untouched - [pendingDownload] is simply discarded, same as cancelling the SAF picker itself. */
    fun dismissLocalOverwrite() {
        localOverwriteConfirm = null
        pendingDownload = null
    }

    private var uploadInitialDirectory: String? = null

    fun requestUpload(connection: SshConnection, initialDirectory: String? = null) {
        uploadSourcePrompt = connection
        uploadInitialDirectory = initialDirectory
    }

    fun dismissUploadSourcePrompt() {
        uploadSourcePrompt = null
        uploadInitialDirectory = null
    }

    /** Reads and clears [uploadSourcePrompt] itself, so the picker's result callback only has to pass what the picker returned. */
    fun uploadSourcePicked(source: Uri?, sourceName: String) {
        val connection = uploadSourcePrompt
        val initialDirectory = uploadInitialDirectory
        uploadSourcePrompt = null
        uploadInitialDirectory = null
        if (connection == null || source == null) return // cancelled the SAF picker
        uploadDestinationPrompt = PendingUpload(connection, source, sourceName, initialDirectory)
    }

    fun dismissUploadDestinationPrompt() {
        uploadDestinationPrompt = null
    }

    fun confirmUploadDestination(remotePath: String) {
        val pending = uploadDestinationPrompt ?: return
        if (remotePath.isBlank()) return
        uploadDestinationPrompt = null
        val trimmed = remotePath.trim()
        scope.launch {
            pending.connection.remoteFileExists(trimmed).fold(
                onSuccess = { exists ->
                    if (exists) {
                        overwriteConfirm = OverwriteRequest(pending, trimmed)
                    } else {
                        startUpload(pending, trimmed)
                    }
                },
                onFailure = { e ->
                    // Never guess: if we can't tell whether the destination already exists, refuse
                    // and not risk a silent clobber.
                    error =
                        "Couldn't check whether \"$trimmed\" already exists on the server: ${e.message}. Nothing was uploaded."
                },
            )
        }
    }

    fun confirmOverwrite() {
        val request = overwriteConfirm ?: return
        overwriteConfirm = null
        startUpload(request.pending, request.remotePath)
    }

    fun dismissOverwrite() {
        overwriteConfirm = null
    }

    private fun startDownload(connection: SshConnection, remotePath: String, destination: Uri) {
        val label = remotePath.substringAfterLast('/').ifEmpty { remotePath }
        activeConnection = connection
        cancelledByUser = false
        _progress.value = TransferProgress(label, 0L, null)
        activeJob =
            scope.launch {
                val outcome =
                    connection.downloadFile(remotePath, destination) { transferred, total ->
                        _progress.value =
                            TransferProgress(label, transferred, total.takeIf { it >= 0 })
                    }
                finish(label, "Downloaded", outcome)
            }
    }

    private fun startUpload(pending: PendingUpload, remotePath: String) {
        val label = remotePath.substringAfterLast('/').ifEmpty { remotePath }
        activeConnection = pending.connection
        cancelledByUser = false
        _progress.value = TransferProgress(label, 0L, null)
        activeJob =
            scope.launch {
                val outcome =
                    pending.connection.uploadFile(
                        remotePath,
                        pending.source
                    ) { transferred, total ->
                        _progress.value =
                            TransferProgress(label, transferred, total.takeIf { it >= 0 })
                    }
                finish(label, "Uploaded", outcome)
            }
    }

    private fun finish(label: String, verb: String, outcome: Result<Long>) {
        _progress.value = null
        activeConnection = null
        if (cancelledByUser) return // the user already dismissed this - don't pop a stale result/error over it
        outcome.fold(
            onSuccess = { bytes ->
                result = TransferUiResult(
                    label,
                    success = true,
                    message = "$verb ${formatByteCount(bytes)}"
                )
            },
            onFailure = { e ->
                result =
                    TransferUiResult(label, success = false, message = e.message ?: "$verb failed")
            },
        )
    }

    /** Best-effort on an SCP fallback - see `SshConnection.cancelActiveTransfer`. */
    fun cancelActiveTransfer() {
        cancelledByUser = true
        activeConnection?.cancelActiveTransfer()
        activeJob?.cancel()
        activeJob = null
        activeConnection = null
        _progress.value = null
    }

    fun dismissResult() {
        result = null
    }

    fun clearError() {
        error = null
    }
}

internal fun formatByteCount(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

@Composable
internal fun rememberFileTransferController(): FileTransferController {
    val scope = rememberCoroutineScope()
    return remember { FileTransferController(scope) }
}
