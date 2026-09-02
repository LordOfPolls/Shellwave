package io.github.lordofpolls.shellwave.ssh

import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.BiometricGate
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.crypto.VaultCrypto
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class ProxyChainFakeHostDao(private val hosts: Map<Long, HostEntity>) : HostDao {
    override fun observeAll(): Flow<List<HostEntity>> = TODO()
    override fun observeRecents(limit: Int): Flow<List<HostEntity>> = TODO()
    override suspend fun getById(id: Long): HostEntity? = hosts[id]
    override suspend fun insert(host: HostEntity): Long = TODO()
    override suspend fun update(host: HostEntity) = TODO()
    override suspend fun delete(host: HostEntity) = TODO()
    override suspend fun getProxyJumpDependents(hostId: Long): List<HostEntity> = TODO()
    override suspend fun countOtherHostsUsingCredential(credentialId: Long, excludeHostId: Long): Int = TODO()
    override suspend fun touchLastConnected(id: Long, timestamp: Long) = TODO()
}

/** Never reached: [ProxyChainFakeCredentialVault] overrides [CredentialVault.resolve]. */
private class ProxyChainFakeCredentialDao : CredentialDao {
    override suspend fun getById(id: Long): CredentialEntity? = TODO()
    override fun observeAll(): Flow<List<CredentialEntity>> = TODO()
    override suspend fun insert(credential: CredentialEntity): Long = TODO()
    override suspend fun update(credential: CredentialEntity) = TODO()
    override suspend fun delete(credential: CredentialEntity) = TODO()
}

/** The real decrypt path needs Android Keystore, which JVM tests don't have. */
private class ProxyChainFakeCredentialVault :
    CredentialVault(VaultCrypto(), ProxyChainFakeCredentialDao(), BiometricGate(), KeyboardInteractiveGate()) {
    override suspend fun resolve(
        credentialId: Long,
        activity: FragmentActivity?,
        trigger: TriggerAuth?,
    ): AuthMethod = AuthMethod.Password("fake")
}

class ProxyChainTest {

    private fun host(id: Long, hostname: String = "10.0.0.$id", proxyJumpHostId: Long? = null) =
        HostEntity(
            id = id,
            label = null,
            hostname = hostname,
            port = 22,
            username = "user",
            credentialId = 1L,
            lastConnectedAt = null,
            createdAt = 0L,
            resilientSession = false,
            proxyJumpHostId = proxyJumpHostId,
        )

    @Test
    fun `two hop chain resolves farthest first`() = runBlocking {
        val bastion = host(id = 1, hostname = "bastion.example.com")
        val middle = host(id = 2, hostname = "middle.example.com", proxyJumpHostId = 1)
        val target = host(id = 3, hostname = "target.example.com", proxyJumpHostId = 2)
        val hostDao = ProxyChainFakeHostDao(mapOf(1L to bastion, 2L to middle, 3L to target))

        val chain = resolveProxyChain(target, hostDao)

        assertEquals(listOf("bastion.example.com", "middle.example.com"), chain.map { it.hostname })
    }

    @Test
    fun `resolveProxyHops resolves each hop's credential`() = runBlocking {
        val bastion = host(id = 1, hostname = "bastion.example.com")
        val target = host(id = 2, hostname = "target.example.com", proxyJumpHostId = 1)
        val hostDao = ProxyChainFakeHostDao(mapOf(1L to bastion, 2L to target))

        val hops = resolveProxyHops(target, hostDao, ProxyChainFakeCredentialVault(), activity = null)

        assertEquals(1, hops.size)
        assertEquals("bastion.example.com", hops.single().hostname)
        assertEquals(AuthMethod.Password("fake"), hops.single().authMethod)
    }

    @Test
    fun `cycle throws naming the loop`() {
        val a = host(id = 1, hostname = "a.example.com", proxyJumpHostId = 2)
        val b = host(id = 2, hostname = "b.example.com", proxyJumpHostId = 1)
        val hostDao = ProxyChainFakeHostDao(mapOf(1L to a, 2L to b))

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { resolveProxyChain(a, hostDao) }
        }

        assertTrue(error.message.orEmpty(), error.message?.contains("cycle") == true)
    }

    @Test
    fun `dangling jump host throws`() {
        val target = host(id = 1, hostname = "target.example.com", proxyJumpHostId = 99)
        val hostDao = ProxyChainFakeHostDao(mapOf(1L to target))

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { resolveProxyChain(target, hostDao) }
        }

        assertTrue(error.message.orEmpty(), error.message?.contains("no longer exists") == true)
    }
}
