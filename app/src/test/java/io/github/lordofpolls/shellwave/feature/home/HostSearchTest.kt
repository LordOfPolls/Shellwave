package io.github.lordofpolls.shellwave.feature.home

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [hostMatches] - the pure predicate behind Hosts' 8+ search field. */
class HostSearchTest {

    private fun host(
        label: String? = "bastion",
        hostname: String = "10.0.0.1",
        username: String = "alice"
    ) =
        HostEntity(
            id = 1,
            label = label,
            hostname = hostname,
            port = 22,
            username = username,
            credentialId = 1L,
            lastConnectedAt = null,
            createdAt = 0L,
        )

    @Test
    fun `blank query matches everything`() {
        assertTrue(hostMatches(host(), ""))
        assertTrue(hostMatches(host(), "   "))
    }

    @Test
    fun `matches the label case-insensitively`() {
        assertTrue(hostMatches(host(label = "Prod DB"), "prod"))
    }

    @Test
    fun `matches the hostname when there is no label`() {
        assertTrue(hostMatches(host(label = null, hostname = "203.0.113.5"), "203.0.113"))
    }

    @Test
    fun `matches the username`() {
        assertTrue(hostMatches(host(username = "deploy"), "depl"))
    }

    @Test
    fun `no match returns false`() {
        assertFalse(
            hostMatches(
                host(label = "bastion", hostname = "10.0.0.1", username = "alice"),
                "staging"
            )
        )
    }
}
