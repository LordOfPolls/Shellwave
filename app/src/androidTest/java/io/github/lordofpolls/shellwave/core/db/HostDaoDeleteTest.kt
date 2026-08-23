package io.github.lordofpolls.shellwave.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the delete-a-host feature added to close "there is no way to delete a host": the
 * getProxyJumpDependents and countOtherHostsUsingCredential queries the MainActivity delete flow
 * relies on, plus the FK behaviours (`port_forwards` CASCADE, `scripts.targetHostId` SET_NULL,
 * `hosts.proxyJumpHostId` RESTRICT) they exist alongside; see hostDeleteBlockReason's doc for why
 * RESTRICT is pre-checked and not caught.
 *
 * Uses an in-memory Room database (real FK enforcement, unlike [MigrationTest] which validates
 * schema shape) instead of mocking the DAOs, so RESTRICT/CASCADE/SET_NULL are exercised for real.
 */
@RunWith(AndroidJUnit4::class)
class HostDaoDeleteTest {
    private lateinit var db: ShellwaveDatabase

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ShellwaveDatabase::class.java).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun insertCredential(): Long =
        runBlocking {
            db.credentialDao().insert(
                CredentialEntity(
                    type = "PASSWORD",
                    label = null,
                    keystoreAlias = null,
                    secretIv = null,
                    secretCiphertext = null,
                    passphraseIv = null,
                    passphraseCiphertext = null,
                    publicKeyText = null,
                    createdAt = 0L
                ),
            )
        }

    private fun insertHost(credentialId: Long, proxyJumpHostId: Long? = null): Long =
        runBlocking {
            db.hostDao().insert(
                HostEntity(
                    label = "host-$credentialId-$proxyJumpHostId",
                    hostname = "10.0.0.1",
                    port = 22,
                    username = "u",
                    credentialId = credentialId,
                    lastConnectedAt = null,
                    createdAt = 0L,
                    proxyJumpHostId = proxyJumpHostId
                ),
            )
        }

    @Test
    fun getProxyJumpDependents_findsHostsThatJumpThroughIt() =
        runBlocking {
            val credentialId = insertCredential()
            val bastionId = insertHost(credentialId)
            val dependentId = insertHost(credentialId, proxyJumpHostId = bastionId)

            val dependents = db.hostDao().getProxyJumpDependents(bastionId)

            assertEquals(1, dependents.size)
            assertEquals(dependentId, dependents.single().id)
        }

    @Test
    fun getProxyJumpDependents_isEmptyWhenNothingJumpsThroughIt() =
        runBlocking {
            val credentialId = insertCredential()
            val hostId = insertHost(credentialId)

            assertEquals(emptyList<HostEntity>(), db.hostDao().getProxyJumpDependents(hostId))
        }

    @Test
    fun deletingAHostWithADependent_violatesTheRestrictForeignKey() =
        runBlocking {
            val credentialId = insertCredential()
            val bastionId = insertHost(credentialId)
            insertHost(credentialId, proxyJumpHostId = bastionId)
            val bastion = db.hostDao().getById(bastionId)!!

            var threw = false
            try {
                db.hostDao().delete(bastion)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                threw = true
            }
            assertEquals(true, threw)
        }

    @Test
    fun countOtherHostsUsingCredential_zeroWhenCredentialIsNotShared() =
        runBlocking {
            val credentialId = insertCredential()
            val hostId = insertHost(credentialId)

            assertEquals(0, db.hostDao().countOtherHostsUsingCredential(credentialId, hostId))
        }

    @Test
    fun countOtherHostsUsingCredential_countsOtherHostsSharingTheSameCredential() =
        runBlocking {
            // Mirrors ~/.ssh/config import attaching one existing credential to two hosts.
            val credentialId = insertCredential()
            val firstHostId = insertHost(credentialId)
            insertHost(credentialId)

            assertEquals(1, db.hostDao().countOtherHostsUsingCredential(credentialId, firstHostId))
        }

    @Test
    fun deletingAHostWithNoDependentsAndAnUnsharedCredential_removesBothRows() =
        runBlocking {
            val credentialId = insertCredential()
            val hostId = insertHost(credentialId)
            val host = db.hostDao().getById(hostId)!!

            db.hostDao().delete(host)
            if (db.hostDao().countOtherHostsUsingCredential(credentialId, hostId) == 0) {
                db.credentialDao().getById(credentialId)?.let { db.credentialDao().delete(it) }
            }

            assertNull(db.hostDao().getById(hostId))
            assertNull(db.credentialDao().getById(credentialId))
        }

    @Test
    fun deletingAHostWhoseCredentialIsShared_leavesTheCredentialForTheOtherHost() =
        runBlocking {
            val credentialId = insertCredential()
            val firstHostId = insertHost(credentialId)
            insertHost(credentialId)
            val first = db.hostDao().getById(firstHostId)!!

            db.hostDao().delete(first)
            if (db.hostDao().countOtherHostsUsingCredential(credentialId, firstHostId) == 0) {
                db.credentialDao().getById(credentialId)?.let { db.credentialDao().delete(it) }
            }

            assertNotNull(db.credentialDao().getById(credentialId))
        }
}
