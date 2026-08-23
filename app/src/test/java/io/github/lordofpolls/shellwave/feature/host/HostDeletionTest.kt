package io.github.lordofpolls.shellwave.feature.host

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [hostDeleteBlockReason] - the pure decision logic behind the host-delete RESTRICT
 * pre-check (see that function's doc for why this blocks rather than repairs). The DAO query that
 * supplies its `dependents` list needs Room/a device and is covered separately in
 * `app/src/androidTest`.
 */
class HostDeletionTest {

    private fun host(id: Long, label: String? = null, hostname: String = "10.0.0.$id") =
        HostEntity(
            id = id,
            label = label,
            hostname = hostname,
            port = 22,
            username = "user",
            credentialId = 1L,
            lastConnectedAt = null,
            createdAt = 0L,
        )

    @Test
    fun `no dependents allows the delete`() {
        assertNull(hostDeleteBlockReason(host(1, "bastion"), emptyList()))
    }

    @Test
    fun `a single dependent is named by label`() {
        val bastion = host(1, "bastion")
        val dependent = host(2, "prod-db")
        val reason = hostDeleteBlockReason(bastion, listOf(dependent))
        assertTrue(reason!!.contains("bastion"))
        assertTrue(reason.contains("prod-db"))
        assertTrue(reason.contains("it"))
    }

    @Test
    fun `a dependent with no label falls back to its hostname`() {
        val bastion = host(1, "bastion")
        val dependent = host(2, label = null, hostname = "10.0.0.9")
        val reason = hostDeleteBlockReason(bastion, listOf(dependent))
        assertTrue(reason!!.contains("10.0.0.9"))
    }

    @Test
    fun `multiple dependents are all named and pluralised`() {
        val bastion = host(1, "bastion")
        val a = host(2, "a")
        val b = host(3, "b")
        val reason = hostDeleteBlockReason(bastion, listOf(a, b))
        assertTrue(reason!!.contains("a"))
        assertTrue(reason.contains("b"))
        assertTrue(reason.contains("them"))
    }

    @Test
    fun `the host being deleted is named in the message`() {
        val bastion = host(1, "jump-box")
        val reason = hostDeleteBlockReason(bastion, listOf(host(2, "dependent")))
        assertTrue(reason!!.startsWith("Can't delete jump-box"))
    }

    @Test
    fun `a host with no label is named by hostname in the message`() {
        val bastion = host(1, label = null, hostname = "203.0.113.5")
        val reason = hostDeleteBlockReason(bastion, listOf(host(2, "dependent")))
        assertEquals(true, reason!!.startsWith("Can't delete 203.0.113.5"))
    }
}
