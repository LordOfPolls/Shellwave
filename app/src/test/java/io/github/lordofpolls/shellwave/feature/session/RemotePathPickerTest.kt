package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.ssh.RemoteEntry
import io.github.lordofpolls.shellwave.ssh.expandTildeForSftp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remote path picker's arithmetic. Its UI needs a device; the arithmetic underneath does not,
 * and it is where a wrong answer is expensive; a picker that resolves a path incorrectly hands the
 * wrong destination to an upload, which is the one operation in this app that can overwrite
 * something on the far end.
 */
class RemotePathPickerTest {

    private fun file(name: String, path: String = "/x/$name") =
        RemoteEntry(name, path, isDirectory = false)

    private fun dir(name: String, path: String = "/x/$name") =
        RemoteEntry(name, path, isDirectory = true)

    @Test
    fun `the root has no parent`() {
        assertNull(remoteParentPath("/"))
    }

    @Test
    fun `a top-level directory's parent is the root, not an empty string`() {
        assertEquals("/", remoteParentPath("/etc"))
    }

    @Test
    fun `a trailing slash does not add a level to climb`() {
        assertEquals("/var", remoteParentPath("/var/log"))
        assertEquals("/var", remoteParentPath("/var/log/"))
    }

    /** A relative path only reaches this function if a listing was never canonicalized. */
    @Test
    fun `a path with no separator left has no knowable parent`() {
        assertNull(remoteParentPath("logs"))
        assertNull(remoteParentPath(""))
    }

    @Test
    fun `breadcrumbs walk from the root down and each is navigable`() {
        assertEquals(
            listOf(
                RemoteCrumb("/", "/"),
                RemoteCrumb("var", "/var"),
                RemoteCrumb("log", "/var/log")
            ),
            remoteBreadcrumbs("/var/log"),
        )
    }

    @Test
    fun `the root renders as one tappable crumb rather than nothing`() {
        assertEquals(listOf(RemoteCrumb("/", "/")), remoteBreadcrumbs("/"))
    }

    @Test
    fun `a trailing slash does not produce an empty final crumb`() {
        assertEquals(remoteBreadcrumbs("/var/log"), remoteBreadcrumbs("/var/log/"))
    }

    /**
     * Joining is where an off-by-one separator becomes a real file in the wrong place: `/` is the only
     * directory whose separator is already there, and `//home/x` is not `/home/x` on every server.
     */
    @Test
    fun `joining a directory to a name never doubles or drops the separator`() {
        assertEquals("/etc/hosts", remoteChildPath("/etc", "hosts"))
        assertEquals("/etc/hosts", remoteChildPath("/etc/", "hosts"))
        assertEquals("/hosts", remoteChildPath("/", "hosts"))
    }

    @Test
    fun `the picker opens in the directory the typed path is already pointing at`() {
        assertEquals("/var/log", startingDirectoryFor("/var/log/syslog"))
        assertEquals("/var/log", startingDirectoryFor("/var/log/"))
        assertEquals("/", startingDirectoryFor("/syslog"))
    }

    /** A bare filename - which is what an upload prompt is pre-filled with - says nothing about location, so the picker starts where the SFTP session starts. */
    @Test
    fun `a bare filename opens at the session's starting directory`() {
        assertEquals(".", startingDirectoryFor("backup.tar.gz"))
        assertEquals(".", startingDirectoryFor(""))
        assertEquals(".", startingDirectoryFor("   "))
    }

    @Test
    fun `directories sort above files, and names sort without regard to case`() {
        val entries = listOf(file("zebra"), dir("Photos"), file("Apple"), dir("apps"))

        assertEquals(
            listOf("apps", "Photos", "Apple", "zebra"),
            sortedForPicker(entries).map { it.name })
    }

    /** Hiding dotfiles would hide `~/.ssh/config`, which is one of the paths this picker exists to reach. */
    @Test
    fun `dotfiles are listed like anything else`() {
        val entries = listOf(file("notes"), file(".bashrc"), dir(".ssh"))

        assertEquals(listOf(".ssh", ".bashrc", "notes"), sortedForPicker(entries).map { it.name })
    }

    /**
     * SFTP has no tilde expansion: `~` is an ordinary filename to the protocol, so a typed `~/logs`
     * would be looked up as a directory literally called `~` and fail on nearly every server.
     */
    @Test
    fun `a typed tilde becomes a relative path the server can canonicalize`() {
        assertEquals(".", expandTildeForSftp("~"))
        assertEquals("./logs", expandTildeForSftp("~/logs"))
        assertEquals(".", expandTildeForSftp(""))
    }

    /** There is no protocol-level way to resolve another user's home, so rewriting this could only guess - failing as a missing path is the honest answer. */
    @Test
    fun `another user's home is left alone rather than guessed at`() {
        assertEquals("~deploy/logs", expandTildeForSftp("~deploy/logs"))
    }

    @Test
    fun `an absolute path is passed through untouched`() {
        assertEquals("/var/log", expandTildeForSftp("/var/log"))
    }
}
