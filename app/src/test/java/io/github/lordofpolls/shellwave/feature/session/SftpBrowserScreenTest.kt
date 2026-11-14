package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.ssh.CaptureResult
import io.github.lordofpolls.shellwave.ssh.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.DateFormat
import java.util.Date

/**
 * [entryDetail]'s -1 sentinels come straight off the wire (an SFTP server that omits SIZE or
 * ACMODTIME), so a wrong read here would show a fabricated size or date for a field the server
 * never sent.
 */
class SftpBrowserScreenTest {

    @Test
    fun `a directory never shows a size, even if one were somehow set`() {
        val dir = RemoteEntry("logs", "/var/logs", isDirectory = true, size = 4096L)
        assertNull(entryDetail(dir))
    }

    @Test
    fun `a file with both fields shows size and date, mtime read as seconds not millis`() {
        val mtimeSeconds = 1_700_000_000L
        val file = RemoteEntry("a.txt", "/x/a.txt", isDirectory = false, size = 2048L, mtime = mtimeSeconds)

        val expectedDate = DateFormat.getDateInstance().format(Date(mtimeSeconds * 1000))
        assertEquals("2.0 KB · $expectedDate", entryDetail(file))
    }

    @Test
    fun `a missing mtime shows no date`() {
        val file = RemoteEntry("a.txt", "/x/a.txt", isDirectory = false, size = 10L, mtime = -1L)
        assertEquals("10 B", entryDetail(file))
    }

    @Test
    fun `a missing size shows no size`() {
        val file = RemoteEntry("a.txt", "/x/a.txt", isDirectory = false, size = -1L, mtime = -1L)
        assertNull(entryDetail(file))
    }
}

class SftpBrowserHistoryTest {

    @Test
    fun `navigate pushes the current path, ignores a same-path move, and pop returns the last one`() {
        val pushed = historyAfterNavigate(emptyList(), "/a", "/a/b")
        assertEquals(listOf("/a"), pushed)
        assertEquals(pushed, historyAfterNavigate(pushed, "/a/b", "/a/b"))

        val twice = historyAfterNavigate(pushed, "/a/b", "/a/b/c")
        assertEquals("/a/b" to listOf("/a"), popHistory(twice))
        assertNull(popHistory(emptyList()))
    }
}

class CdCommandTest {

    @Test
    fun `cd quotes a path with a space and a single quote and ends with a carriage return`() {
        assertEquals("cd -- '/srv/it'\\''s here'\r", cdCommand("/srv/it's here"))
    }

    @Test
    fun `cd refuses a path with control characters`() {
        for (c in listOf('\u0015', '\u0003', '\u001b', '\r', '\n', '\u007f')) {
            assertNull(cdCommand("/srv/a${c}b"))
        }
    }

    @Test
    fun `local leaf name drops directories and refuses dot names and control characters`() {
        assertEquals("x", safeLocalName("../../databases/x"))
        assertNull(safeLocalName(".."))
        assertNull(safeLocalName("."))
        assertNull(safeLocalName("a/"))
        assertNull(safeLocalName("a\u0000b"))
        assertEquals("my file.txt", safeLocalName("my file.txt"))
    }
}

class SftpRemoteCommandTest {

    @Test
    fun `cp quotes every source and the destination`() {
        assertEquals(
            "cp -R -- '/a/it'\\''s' '/a/two words' '/d e'",
            cpCommand(listOf("/a/it's", "/a/two words"), "/d e"),
        )
    }

    @Test
    fun `tar prefixes names with dot-slash so a leading dash is not read as an option`() {
        assertEquals(
            "tar -czf '/w/a.tar.gz' -C '/w' './-rf' './my file' './it'\\''s'",
            tarCommand("/w/a.tar.gz", "/w", listOf("-rf", "my file", "it's")),
        )
    }

    @Test
    fun `archive name defaults to the item name for one item and archive otherwise`() {
        assertEquals("docs.tar.gz", defaultArchiveName(listOf("docs")))
        assertEquals("archive.tar.gz", defaultArchiveName(listOf("a", "b")))
    }

    @Test
    fun `capture failures say what happened and a timeout warns it may still be running`() {
        val timeout = CaptureResult("", "", false, false, null, "Timed out after 120s")
        assertEquals(true, captureFailure("Copy", timeout)!!.contains("may still be running"))
        assertEquals("Copy failed (exit 1): nope", captureFailure("Copy", CaptureResult("", "nope\n", false, false, 1, null)))
        assertNull(captureFailure("Copy", CaptureResult("", "", false, false, 0, null)))
    }

    @Test
    fun `batch download summary reports successes, failures and skipped folders`() {
        assertEquals(
            "Downloaded 1 file.\nCouldn't download: b (nope)\nFolders aren't downloaded — compress them first: d",
            batchDownloadSummary(1, listOf("b (nope)"), listOf("d")),
        )
    }
}
