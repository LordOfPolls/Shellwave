package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.runtime.saveable.SaverScope
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentConsentTest {

    private fun host(forwarding: Boolean = true, keyIds: String = "[1,2]") = HostEntity(
        id = 9L,
        label = null,
        hostname = "h",
        port = 22,
        username = "u",
        credentialId = 1L,
        lastConnectedAt = null,
        createdAt = 0L,
        agentForwarding = forwarding,
        agentKeyIdsJson = keyIds,
    )

    private fun credential(id: Long, type: String = "PRIVATE_KEY") = CredentialEntity(
        id = id,
        type = type,
        label = null,
        keystoreAlias = null,
        secretIv = null,
        secretCiphertext = null,
        passphraseIv = null,
        passphraseCiphertext = null,
        publicKeyText = null,
        createdAt = 0L,
    )

    private fun offered(host: HostEntity?, requested: List<Long>, existing: Map<Long, CredentialEntity>) =
        runBlocking { offeredAgentKeys(host, requested, existing::get).map { it.id } }

    private val both = mapOf(1L to credential(1), 2L to credential(2))

    @Test
    fun `a restored prompt offers the keys that still exist`() {
        assertEquals(listOf(1L, 2L), offered(host(), listOf(1, 2), both))
        assertEquals(listOf(2L), offered(host(), listOf(1, 2), mapOf(2L to credential(2))))
    }

    @Test
    fun `a gone host or switched-off forwarding drops the prompt`() {
        assertEquals(emptyList<Long>(), offered(null, listOf(1, 2), both))
        assertEquals(emptyList<Long>(), offered(host(forwarding = false), listOf(1, 2), both))
    }

    @Test
    fun `a key the host no longer selects or that is not a private key is not offered`() {
        assertEquals(listOf(1L), offered(host(keyIds = "[1]"), listOf(1, 2), both))
        assertEquals(emptyList<Long>(), offered(host(), listOf(1), mapOf(1L to credential(1, "PASSWORD"))))
    }

    @Test
    fun `the saved request is ids only and round trips`() {
        val saver = agentConsentRequestSaver(nonce = 7L)
        val request = AgentConsentRequest(9L, listOf(1L, 2L))
        val saved = with(saver) { SaverScope { true }.save(request) }!!
        assertEquals(request, saver.restore(saved))
        assertNull(with(saver) { SaverScope { true }.save(null) })
    }

    @Test
    fun `a request saved by an earlier process is dropped`() {
        val saved = with(agentConsentRequestSaver(nonce = 7L)) {
            SaverScope { true }.save(AgentConsentRequest(9L, listOf(1L)))
        }!!
        assertNull(agentConsentRequestSaver(nonce = 8L).restore(saved))
    }
}
