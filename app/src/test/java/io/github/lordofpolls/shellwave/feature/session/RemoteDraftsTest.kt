package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.ssh.RemoteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteDraftsTest {

    private val loaded = RemoteText("a", "\n", true, 1L)

    @Test
    fun `a draft is handed back once, to its own session and path only`() {
        RemoteDrafts.retain(7, "/etc/x", RemoteDrafts.Draft(loaded, "ab"))

        assertNull(RemoteDrafts.take(7, "/etc/y"))
        assertNull(RemoteDrafts.take(8, "/etc/x"))
        assertEquals("ab", RemoteDrafts.take(7, "/etc/x")?.text)
        assertNull(RemoteDrafts.take(7, "/etc/x"))
    }
}
