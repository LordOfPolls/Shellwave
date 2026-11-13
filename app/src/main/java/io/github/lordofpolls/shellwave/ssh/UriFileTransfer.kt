package io.github.lordofpolls.shellwave.ssh

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import net.schmizz.sshj.xfer.InMemoryDestFile
import net.schmizz.sshj.xfer.InMemorySourceFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Lets [SshConnection.uploadFile] hand a picked document straight to sshj instead of copying it
 * through a temp file. [InMemorySourceFile]'s defaults (0644, no atime/mtime) are right: a SAF
 * document has no POSIX permissions of its own to preserve.
 */
internal class UriSourceFile(private val context: Context, private val uri: Uri) :
    InMemorySourceFile() {
    private val displayName: String? by lazy { queryDisplayName(context, uri) }
    private val size: Long by lazy { queryLength(context, uri) }

    override fun getName(): String =
        displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    /** -1 when the provider reports no size; `SshConnection.uploadFile` reads that as unknown. */
    override fun getLength(): Long = size

    override fun getInputStream(): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open $uri for reading")
}

internal class UriDestFile(private val context: Context, private val uri: Uri) :
    InMemoryDestFile() {
    /** Present only because [net.schmizz.sshj.xfer.LocalDestFile] requires it. */
    override fun getLength(): Long = 0L

    override fun getOutputStream(): OutputStream = getOutputStream(false)

    override fun getOutputStream(append: Boolean): OutputStream =
        context.contentResolver.openOutputStream(uri, openMode(append))
            ?: throw IOException("Could not open $uri for writing")
}

/**
 * "wt", not "w": plain "w" does not truncate on many document providers, so overwriting a longer
 * file would leave its old trailing bytes behind.
 */
internal fun openMode(append: Boolean): String = if (append) "wa" else "wt"

/**
 * Why an upload can't be confirmed complete, or null when it can. [remoteSize] is null when the
 * post-upload `stat` failed, which is not proof of success. [expectedSize] is -1 when the source
 * size is unknown, so only the `stat` failure can be reported then.
 */
internal fun uploadVerificationError(remoteSize: Long?, expectedSize: Long): String? =
    when {
        remoteSize == null -> "Uploaded, but could not confirm the file's size on the server"
        expectedSize >= 0 && remoteSize != expectedSize ->
            "Uploaded, but the server now reports $remoteSize bytes for a $expectedSize-byte file - the transfer looks incomplete"

        else -> null
    }

private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

private fun queryLength(context: Context, uri: Uri): Long =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            }
    }.getOrNull() ?: -1L
