package io.github.lordofpolls.shellwave.ssh

import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.BiometricGate
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.crypto.VaultCrypto
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.ssh.KeyboardInteractiveGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeHostDao(private val hosts: Map<Long, HostEntity>) : HostDao {
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

/** Never reached: [FakeCredentialVault] overrides [CredentialVault.resolve]. */
private class FakeCredentialDao : CredentialDao {
    override suspend fun getById(id: Long): CredentialEntity? = TODO()
    override fun observeAll(): Flow<List<CredentialEntity>> = TODO()
    override suspend fun insert(credential: CredentialEntity): Long = TODO()
    override suspend fun update(credential: CredentialEntity) = TODO()
    override suspend fun setCertificate(id: Long, certificate: String?) = TODO()
    override suspend fun delete(credential: CredentialEntity) = TODO()
}

/** The real decrypt path needs Android Keystore, which JVM tests don't have. */
private class FakeCredentialVault :
    CredentialVault(VaultCrypto(), FakeCredentialDao(), BiometricGate(), KeyboardInteractiveGate()) {
    override suspend fun resolve(
        credentialId: Long,
        activity: FragmentActivity?,
        trigger: TriggerAuth?,
    ): AuthMethod = AuthMethod.Password("fake")
}

class ConnectionResolverTest {

    private fun host(id: Long, proxyJumpHostId: Long? = null, resilientSession: Boolean = false) =
        HostEntity(
            id = id,
            label = null,
            hostname = "10.0.0.$id",
            port = 22,
            username = "user",
            credentialId = 1L,
            lastConnectedAt = null,
            createdAt = 0L,
            resilientSession = resilientSession,
            proxyJumpHostId = proxyJumpHostId,
        )

    @Test
    fun `resolves a jump host into one hop and carries resilientSession`() = runBlocking {
        val jump = host(id = 1)
        val target = host(id = 2, proxyJumpHostId = 1, resilientSession = true)
        val hostDao = FakeHostDao(mapOf(1L to jump, 2L to target))
        val credentialVault = FakeCredentialVault()

        val spec = resolveConnectionSpec(target, hostDao, credentialVault, activity = null)

        assertEquals(1, spec.proxyHops.size)
        assertEquals(jump.hostname, spec.proxyHops.single().hostname)
        assertEquals(target.id, spec.hostId)
        assertEquals(true, spec.resilientSession)
    }
}
