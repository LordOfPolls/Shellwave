package io.github.lordofpolls.shellwave.feature.home

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [savedHostsMatching] decides whether quick connect offers a saved credential instead of asking
 * for a password. Getting it wrong connects somewhere the user did not type, so the cases that must
 * not match matter as much as the ones that must.
 */
class SavedHostsMatchingTest {

    private fun host(
        id: Long,
        hostname: String,
        username: String,
        port: Int = 22,
        label: String? = null,
    ) = HostEntity(
        id = id,
        label = label,
        hostname = hostname,
        port = port,
        username = username,
        credentialId = id,
        lastConnectedAt = null,
        createdAt = 0L,
    )

    private val betty = host(1, "betty.example.com", "dave", label = "BETTY")

    @Test
    fun `the exact triple matches`() {
        val target = QuickConnectTarget("dave", "betty.example.com", 22)

        assertEquals(listOf(betty), savedHostsMatching(target, listOf(betty)))
    }

    /** DNS is case-insensitive, so `BETTY.example.com` is the same machine. */
    @Test
    fun `hostname matches regardless of case`() {
        val target = QuickConnectTarget("dave", "BETTY.Example.COM", 22)

        assertEquals(listOf(betty), savedHostsMatching(target, listOf(betty)))
    }

    /** POSIX usernames are case-sensitive - `Dave` and `dave` can be two different accounts. */
    @Test
    fun `username case is significant`() {
        val target = QuickConnectTarget("Dave", "betty.example.com", 22)

        assertTrue(savedHostsMatching(target, listOf(betty)).isEmpty())
    }

    @Test
    fun `a different port is a different target`() {
        val target = QuickConnectTarget("dave", "betty.example.com", 2222)

        assertTrue(savedHostsMatching(target, listOf(betty)).isEmpty())
    }

    /**
     * The label is a nickname the user is free to type into the quick-connect box. Matching on it would
     * take "connect to a machine called BETTY" and silently turn it into "connect to betty.example.com
     * as dave", which is not what was asked for.
     */
    @Test
    fun `a label is never matched on`() {
        val target = QuickConnectTarget("root", "BETTY", 22)

        assertTrue(savedHostsMatching(target, listOf(betty)).isEmpty())
    }

    /**
     * Two saved hosts may legitimately point at one address with different settings - HomeScreen's own
     * doc says so - so this returns both and lets the caller ask, and not picking one.
     */
    @Test
    fun `every match is returned, not just the first`() {
        val viaProxy = host(2, "betty.example.com", "dave", label = "BETTY (via bastion)")
        val target = QuickConnectTarget("dave", "betty.example.com", 22)

        assertEquals(listOf(betty, viaProxy), savedHostsMatching(target, listOf(betty, viaProxy)))
    }

    @Test
    fun `an unsaved target matches nothing`() {
        val target = QuickConnectTarget("dave", "somewhere-else.example.com", 22)

        assertTrue(savedHostsMatching(target, listOf(betty)).isEmpty())
    }

    /**
     * The parser defaults a missing username to `root`, so typing a bare hostname asks for
     * root@that-host and does not find a host saved under another username. That is deliberate:
     * offering `dave@betty` for input that says `root@betty` would answer a question nobody asked.
     */
    @Test
    fun `a bare hostname does not match another username's host`() {
        val target = parseQuickConnect("betty.example.com")!!

        assertTrue(savedHostsMatching(target, listOf(betty)).isEmpty())
    }
}
