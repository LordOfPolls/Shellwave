package io.github.lordofpolls.shellwave.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.ssh.KeyboardInteractiveGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory stand-in for Room - CredentialVault.resolve never needs a real database, only a row. */
private class FakeCredentialDao : CredentialDao {
    private val rows = mutableMapOf<Long, CredentialEntity>()

    override suspend fun getById(id: Long): CredentialEntity? = rows[id]
    override fun observeAll(): Flow<List<CredentialEntity>> = MutableStateFlow(rows.values.toList())
    override suspend fun insert(credential: CredentialEntity): Long {
        val id = (rows.keys.maxOrNull() ?: 0L) + 1
        rows[id] = credential.copy(id = id)
        return id
    }

    override suspend fun update(credential: CredentialEntity) {
        rows[credential.id] = credential
    }

    override suspend fun setCertificate(id: Long, certificate: String?) {
        rows[id]?.let { rows[id] = it.copy(certificate = certificate) }
    }

    override suspend fun delete(credential: CredentialEntity) {
        rows.remove(credential.id)
    }
}

/**
 * `CredentialVault` needs a real [VaultCrypto], which touches the real Android Keystore in its
 * constructor, so this runs instrumented alongside VaultCryptoTest rather than as a JVM unit test -
 * see `testOptions.unitTests` in app/build.gradle.kts.
 *
 * The fixture credential's ciphertext is deliberately garbage, not a validly sealed secret: the
 * point of [resolve_biometricGatedCredential_withNullActivity_throwsAndNeverPrompts] is that
 * `resolve` refuses before it ever calls into VaultCrypto/BiometricPrompt for a `null` activity, so
 * nothing here needs to actually decrypt.
 */
@RunWith(AndroidJUnit4::class)
class CredentialVaultResolveTest {

    @Test
    fun resolve_biometricGatedCredential_withNullActivity_throwsAndNeverPrompts() {
        val vaultCrypto = VaultCrypto()
        val dao = FakeCredentialDao()
        val credentialId = runBlocking {
            dao.insert(
                CredentialEntity(
                    type = CredentialType.PASSWORD.name,
                    label = "test",
                    keystoreAlias = VaultCrypto.ALIAS_BIOMETRIC_WINDOWED,
                    secretIv = byteArrayOf(1, 2, 3),
                    secretCiphertext = byteArrayOf(4, 5, 6),
                    passphraseIv = null,
                    passphraseCiphertext = null,
                    publicKeyText = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        val vault = CredentialVault(vaultCrypto, dao, BiometricGate(), KeyboardInteractiveGate())

        // A background trigger always passes activity = null (ScriptTriggerService's own contract) -
        // this must refuse immediately rather than block on a BiometricPrompt with no Activity to
        // host one, which is what would happen if the null check were ever bypassed or ordered after
        // a Keystore call.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { vault.resolve(credentialId, activity = null) }
        }
        assertTrue(
            "must refuse for the right reason (no activity), not fail some other way: ${thrown.message}",
            thrown.message?.contains("needs an activity to prompt from") == true,
        )
    }
}
