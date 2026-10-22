package io.github.lordofpolls.shellwave.feature.keys

import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyUsageTest {

    private fun host(
        id: Long,
        credentialId: Long = 99L,
        forwarding: Boolean = false,
        keyIds: String = "[]",
    ) = HostEntity(
        id = id,
        label = "h$id",
        hostname = "10.0.0.$id",
        port = 22,
        username = "user",
        credentialId = credentialId,
        lastConnectedAt = null,
        createdAt = 0L,
        agentForwarding = forwarding,
        agentKeyIdsJson = keyIds,
    )

    @Test
    fun `a key nothing touches is unused`() {
        assertEquals("Unused", keyUsageSummary(1, listOf(host(1), host(2, forwarding = true, keyIds = "[7]"))))
    }

    @Test
    fun `login and forwarding are counted separately`() {
        val hosts = listOf(
            host(1, credentialId = 1),
            host(2, forwarding = true, keyIds = "[1,5]"),
            host(3, forwarding = true, keyIds = "[1]"),
        )
        assertEquals("Login for 1 host · Forwarded to 2 hosts", keyUsageSummary(1, hosts))
    }

    @Test
    fun `forwarding alone is not reported as unused`() {
        assertEquals("Forwarded to 1 host", keyUsageSummary(1, listOf(host(1, forwarding = true, keyIds = "[1]"))))
    }

    @Test
    fun `a host with forwarding switched off does not forward its picked keys`() {
        assertEquals("Unused", keyUsageSummary(1, listOf(host(1, forwarding = false, keyIds = "[1]"))))
    }

    @Test
    fun `the delete warning names forwarding hosts and is absent when there are none`() {
        assertNull(keyDeleteForwardingWarning(emptyList()))
        val warning = keyDeleteForwardingWarning(hostsForwardingKey(1, listOf(host(1, forwarding = true, keyIds = "[1]"), host(2))))
        assertEquals("Forwarded to h1. They'll stop offering it.", warning)
    }

    private fun key(id: Long) = CredentialEntity(
        id = id,
        type = "PRIVATE_KEY",
        label = null,
        keystoreAlias = null,
        secretIv = null,
        secretCiphertext = null,
        passphraseIv = null,
        passphraseCiphertext = null,
        publicKeyText = null,
        createdAt = 0L,
    )

    @Test
    fun `only keys with neither a login nor a forwarding use are unused`() {
        val hosts = listOf(
            host(1, credentialId = 1),
            host(2, forwarding = true, keyIds = "[2]"),
            host(3, forwarding = false, keyIds = "[3]"),
        )
        assertEquals(listOf(3L, 4L), unusedKeys(listOf(key(1), key(2), key(3), key(4)), hosts).map { it.id })
    }
}
