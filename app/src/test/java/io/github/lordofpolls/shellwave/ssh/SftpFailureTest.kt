package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** [describeSftpFailure] is as much about what it leaves alone as what it rewrites. */
class SftpFailureTest {
    @Test
    fun failureStatus_isRewrittenPerOperation() {
        val message = describeSftpFailure(
            SFTPException(StatusCode.FAILURE, "Failure"),
            SftpOp.DeleteDir,
            "/home/deploy/notempty"
        )

        assertEquals("the server refused; the directory may not be empty.", message)
    }

    @Test
    fun permissionDenied_namesTheParentDirectoryForAWrite() {
        val message = describeSftpFailure(
            SFTPException(StatusCode.PERMISSION_DENIED, "Permission denied"),
            SftpOp.Upload,
            "/home/deploy/uploads/new.txt"
        )

        assertEquals("the account doesn't have permission on \"/home/deploy/uploads\".", message)
    }

    @Test
    fun permissionDenied_namesThePathItselfForARead() {
        val message = describeSftpFailure(
            SFTPException(StatusCode.PERMISSION_DENIED, "Permission denied"),
            SftpOp.List,
            "/root"
        )

        assertEquals("the account doesn't have permission on \"/root\".", message)
    }

    @Test
    fun noSuchFile_namesThePath() {
        val message = describeSftpFailure(
            SFTPException(StatusCode.NO_SUCH_FILE, "No such file"),
            SftpOp.Delete,
            "/home/deploy/gone.txt"
        )

        assertEquals("\"/home/deploy/gone.txt\" doesn't exist on the server any more.", message)
    }

    @Test
    fun aPlainIOException_passesThroughVerbatim() {
        val message = describeSftpFailure(IOException("boom"), SftpOp.List, "/tmp")

        assertEquals("boom", message)
    }

    @Test
    fun anIOExceptionReadingJustFailure_isRewritten() {
        val message = describeSftpFailure(IOException("Failure"), SftpOp.MakeDir, "/tmp/new")

        assertEquals(
            "the server refused; it may already exist, or the parent directory may not.",
            message
        )
    }

    @Test
    fun anIOExceptionWithARealCause_isNotMistakenForABareStatusName() {
        val message = describeSftpFailure(IOException("Permission denied"), SftpOp.Upload, "/tmp/new")

        assertEquals("Permission denied", message)
    }

    @Test
    fun aNullMessage_isRewrittenRatherThanRenderedAsNull() {
        val message = describeSftpFailure(RuntimeException(), SftpOp.Rename, "/tmp/old")

        assertEquals(
            "the server refused; the new name may already exist, or the two paths may be on different filesystems.",
            message
        )
    }
}
