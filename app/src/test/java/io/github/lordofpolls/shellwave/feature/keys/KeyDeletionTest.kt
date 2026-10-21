package io.github.lordofpolls.shellwave.feature.keys

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDeletionTest {

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
    fun `a key no host uses can be deleted`() {
        assertNull(keyDeleteBlockReason("github", emptyList()))
    }

    @Test
    fun `a key in use names every host using it`() {
        val reason = keyDeleteBlockReason("github", listOf(host(1, "prod-db"), host(2, label = null, hostname = "10.0.0.9")))
        assertTrue(reason!!.contains("github"))
        assertTrue(reason.contains("prod-db"))
        assertTrue(reason.contains("10.0.0.9"))
    }

    @Test
    fun `a count of zero does not block`() {
        assertNull(keyDeleteBlockReason("github", 0))
    }

    @Test
    fun `a count-only block pluralises and names no host`() {
        assertTrue(keyDeleteBlockReason("github", 1)!!.contains("1 host."))
        assertTrue(keyDeleteBlockReason("github", 3)!!.contains("3 hosts."))
    }
}
