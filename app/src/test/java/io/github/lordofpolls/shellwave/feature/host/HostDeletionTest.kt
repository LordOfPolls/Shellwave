package io.github.lordofpolls.shellwave.feature.host

import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHostDao(
    private val hosts: MutableMap<Long, HostEntity>,
    private val dependents: List<HostEntity> = emptyList(),
) : HostDao {
    var deleted: HostEntity? = null
        private set

    override fun observeAll(): Flow<List<HostEntity>> = TODO()
    override fun observeRecents(limit: Int): Flow<List<HostEntity>> = TODO()
    override suspend fun getById(id: Long): HostEntity? = hosts[id]
    override suspend fun insert(host: HostEntity): Long = TODO()
    override suspend fun update(host: HostEntity) = TODO()
    override suspend fun delete(host: HostEntity) {
        deleted = host
        hosts.remove(host.id)
    }
    override suspend fun getProxyJumpDependents(hostId: Long): List<HostEntity> = dependents
    override suspend fun countOtherHostsUsingCredential(credentialId: Long, excludeHostId: Long): Int =
        hosts.values.count { it.credentialId == credentialId && it.id != excludeHostId }
    override suspend fun touchLastConnected(id: Long, timestamp: Long) = TODO()
}

private class FakeCredentialDao(private val credentials: MutableMap<Long, CredentialEntity>) : CredentialDao {
    var deleted: CredentialEntity? = null
        private set

    override suspend fun getById(id: Long): CredentialEntity? = credentials[id]
    override fun observeAll(): Flow<List<CredentialEntity>> = TODO()
    override suspend fun insert(credential: CredentialEntity): Long = TODO()
    override suspend fun update(credential: CredentialEntity) = TODO()
    override suspend fun delete(credential: CredentialEntity) {
        deleted = credential
        credentials.remove(credential.id)
    }
}

/**
 * Covers [hostDeleteBlockReason] and [deleteHostWithCleanup] - the decision logic behind the
 * host-delete RESTRICT pre-check (see that function's doc for why this blocks rather than repairs). The DAO query that
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

    private fun credential(id: Long) = CredentialEntity(
        id = id,
        type = "PASSWORD",
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
    fun `deleting a host also deletes its credential when no other host uses it`() = runBlocking {
        val target = host(1, "target")
        val hostDao = FakeHostDao(mutableMapOf(1L to target))
        val credentialDao = FakeCredentialDao(mutableMapOf(1L to credential(1)))

        val blockReason = deleteHostWithCleanup(target, hostDao, credentialDao)

        assertNull(blockReason)
        assertEquals(target, hostDao.deleted)
        assertEquals(1L, credentialDao.deleted?.id)
    }

    @Test
    fun `deleting a host keeps the credential when another host still uses it`() = runBlocking {
        val target = host(1, "target")
        val otherHost = target.copy(id = 2, label = "other")
        val hostDao = FakeHostDao(mutableMapOf(1L to target, 2L to otherHost))
        val credentialDao = FakeCredentialDao(mutableMapOf(1L to credential(1)))

        val blockReason = deleteHostWithCleanup(target, hostDao, credentialDao)

        assertNull(blockReason)
        assertEquals(target, hostDao.deleted)
        assertNull(credentialDao.deleted)
    }

    @Test
    fun `a blocked delete leaves the host and credential untouched`() = runBlocking {
        val bastion = host(1, "bastion")
        val dependent = host(2, "prod-db")
        val hostDao = FakeHostDao(mutableMapOf(1L to bastion, 2L to dependent), dependents = listOf(dependent))
        val credentialDao = FakeCredentialDao(mutableMapOf(1L to credential(1)))

        val blockReason = deleteHostWithCleanup(bastion, hostDao, credentialDao)

        assertTrue(blockReason!!.contains("prod-db"))
        assertNull(hostDao.deleted)
        assertNull(credentialDao.deleted)
    }
}
