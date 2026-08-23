package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [sessionDisplayName]'s fallback chain. The saved-host cases are cheap to assert; the interesting
 * ones are the two where no host row resolves, since that path parses a `user@host:port` string by
 * hand and is what a quick-connect session always takes.
 */
class SessionDisplayNameTest {
    private fun host(id: Long, label: String?, hostname: String) =
        HostEntity(
            id = id,
            label = label,
            hostname = hostname,
            port = 22,
            username = "polls",
            credentialId = 1,
            lastConnectedAt = null,
            createdAt = 0,
        )

    @Test
    fun `saved host prefers its label`() {
        val hosts = listOf(host(1, "betty", "10.0.0.5"))
        assertEquals("betty", sessionDisplayName(1, "polls@10.0.0.5:22", hosts))
    }

    @Test
    fun `saved host without a label falls back to its hostname`() {
        val hosts = listOf(host(1, null, "10.0.0.5"))
        assertEquals("10.0.0.5", sessionDisplayName(1, "polls@10.0.0.5:22", hosts))
    }

    /** A quick-connect session: no host row exists, so the host portion of the identity is the name. */
    @Test
    fun `null host id uses the host portion of the identity`() {
        assertEquals("example.com", sessionDisplayName(null, "root@example.com:2222", emptyList()))
    }

    /** A host deleted while its session stayed open leaves a hostId that resolves to nothing. */
    @Test
    fun `unresolvable host id uses the host portion of the identity`() {
        val hosts = listOf(host(1, "betty", "10.0.0.5"))
        assertEquals("example.com", sessionDisplayName(99, "root@example.com:22", hosts))
    }

    /** Malformed identity must degrade to showing itself, never to a blank row 1. */
    @Test
    fun `an identity not in user-at-host form degrades to itself`() {
        assertEquals("weird-label", sessionDisplayName(null, "weird-label", emptyList()))
    }
}
