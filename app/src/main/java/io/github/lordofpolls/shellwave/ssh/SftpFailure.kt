package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPException

internal enum class SftpOp { List, Rename, Delete, DeleteDir, MakeDir, Upload, Download, Stat }

/**
 * OpenSSH collapses most errnos - `ENOTEMPTY`, `EEXIST`, `ENOSPC`, `EDQUOT`, `EISDIR` among them -
 * into `SSH_FX_FAILURE` with the text "Failure" and nothing else, so `SFTPException.message` is
 * frequently useless verbatim. [describeSftpFailure] turns that (and sshj's other status codes)
 * into a reason clause a caller can append after its own "Couldn't <verb> \"<name>\": " prefix.
 *
 * A permission error is reported against the parent directory for operations that write a new
 * entry there (rename, delete, mkdir, upload) - that's what actually needs the permission - and
 * against [path] itself for read-only operations, where [path] is already the thing being denied.
 *
 * Anything not covered by a specific status code passes its own message through unchanged, the
 * same policy as [describeConnectFailure], except when that message is missing or is itself just
 * "Failure": then it degrades to the same guess `StatusCode.FAILURE` would produce, since that is
 * what an unset or generic message usually means here. A message naming a real cause (e.g. "No
 * such file") is truthful and left alone even without a `StatusCode` to confirm it.
 */
internal fun describeSftpFailure(e: Throwable, op: SftpOp, path: String): String {
    val statusCode = (e as? SFTPException)?.statusCode
    return when (statusCode) {
        StatusCode.NO_SUCH_FILE, StatusCode.NO_SUCH_PATH ->
            "\"$path\" doesn't exist on the server any more."

        StatusCode.PERMISSION_DENIED ->
            "the account doesn't have permission on \"${permissionSubject(op, path)}\"."

        StatusCode.FAILURE -> failureReasonFor(op)

        StatusCode.OP_UNSUPPORTED -> "this server's SFTP doesn't support that operation."

        StatusCode.NO_CONNECTION, StatusCode.CONNECITON_LOST -> "the SFTP connection dropped."

        StatusCode.FILE_ALREADY_EXISTS -> "\"$path\" already exists on the server."
        StatusCode.DIR_NOT_EMPTY -> "the directory isn't empty."
        StatusCode.NO_SPACE_ON_FILESYSTEM -> "the server has run out of disk space."
        StatusCode.QUOTA_EXCEEDED -> "the account is over its disk quota on the server."
        StatusCode.WRITE_PROTECT -> "the filesystem on the server is read-only."
        StatusCode.NOT_A_DIRECTORY -> "\"$path\" isn't a directory."
        StatusCode.FILE_IS_A_DIRECTORY -> "\"$path\" is a directory, not a file."
        StatusCode.INVALID_FILENAME -> "the server rejected that filename."

        else -> {
            val message = e.message
            if (message.isNullOrBlank() || message.equals(StatusCode.FAILURE.name, ignoreCase = true)) {
                failureReasonFor(op)
            } else {
                message
            }
        }
    }
}

private fun permissionSubject(op: SftpOp, path: String): String =
    when (op) {
        SftpOp.Rename, SftpOp.Delete, SftpOp.DeleteDir, SftpOp.MakeDir, SftpOp.Upload ->
            parentDirectoryOf(path)

        SftpOp.List, SftpOp.Download, SftpOp.Stat -> path
    }

private fun failureReasonFor(op: SftpOp): String =
    when (op) {
        SftpOp.DeleteDir -> "the server refused; the directory may not be empty."
        SftpOp.MakeDir -> "the server refused; it may already exist, or the parent directory may not."
        SftpOp.Rename ->
            "the server refused; the new name may already exist, or the two paths may be on different filesystems."

        SftpOp.Upload -> "the server refused the write; the disk may be full, over quota, or read-only."
        SftpOp.List, SftpOp.Delete, SftpOp.Download, SftpOp.Stat ->
            "the server refused without saying why."
    }

/** A path with no separator left, or none but a leading slash, has no knowable parent below root. */
private fun parentDirectoryOf(path: String): String {
    val trimmed = path.trimEnd('/')
    val separator = trimmed.lastIndexOf('/')
    return when {
        separator > 0 -> trimmed.substring(0, separator)
        separator == 0 -> "/"
        else -> trimmed
    }
}
