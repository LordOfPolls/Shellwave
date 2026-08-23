package io.github.lordofpolls.shellwave.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.Security
import javax.crypto.KeyGenerator

/**
 * AndroidKeyStore only exists on-device, so this runs instrumented and not as a JVM unit test. Only
 * [VaultCrypto.ALIAS_DEFAULT] is covered here, since it needs no human interaction; the biometric
 * aliases' round trips are exercised manually against the running app, because `BiometricPrompt`
 * has no headless test path.
 */
@RunWith(AndroidJUnit4::class)
class VaultCryptoTest {

    @Test
    fun sealThenOpen_returnsOriginalPlaintext() {
        val vaultCrypto = VaultCrypto()
        vaultCrypto.ensureKey(VaultCrypto.ALIAS_DEFAULT, requireBiometric = false)

        val plaintext = "correct horse battery staple".toByteArray()
        val sealed = vaultCrypto.seal(VaultCrypto.ALIAS_DEFAULT, plaintext)
        val opened = vaultCrypto.open(VaultCrypto.ALIAS_DEFAULT, sealed)

        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun sealTwice_producesDifferentIvs() {
        val vaultCrypto = VaultCrypto()
        vaultCrypto.ensureKey(VaultCrypto.ALIAS_DEFAULT, requireBiometric = false)

        val plaintext = "same plaintext both times".toByteArray()
        val first = vaultCrypto.seal(VaultCrypto.ALIAS_DEFAULT, plaintext)
        val second = vaultCrypto.seal(VaultCrypto.ALIAS_DEFAULT, plaintext)

        assertFalse(
            "GCM must never reuse an IV under the same key",
            first.iv.contentEquals(second.iv)
        )
    }

    /**
     * Generating the windowed alias's key never itself needs a biometric prompt - only using it,
     * outside the [VaultCrypto.BIOMETRIC_WINDOW_SECONDS] window, does. So this proves only that
     * `ensureKey` builds a valid `KeyGenParameterSpec` for it and is idempotent; the window's actual
     * reuse behaviour needs a real fingerprint check to observe.
     */
    @Test
    fun ensureKey_windowedBiometricAlias_generatesWithoutRequiringAuthentication() {
        val vaultCrypto = VaultCrypto()
        vaultCrypto.ensureKey(VaultCrypto.ALIAS_BIOMETRIC_WINDOWED, requireBiometric = true)
        // Idempotent, same contract as every other alias - a second call must not throw or attempt
        // to regenerate the key.
        vaultCrypto.ensureKey(VaultCrypto.ALIAS_BIOMETRIC_WINDOWED, requireBiometric = true)
    }

    /**
     * A failing Keystore `Cipher.init` once reported BouncyCastle's `NullPointerException` instead of
     * the real reason.
     *
     * ShellwaveApplication puts BouncyCastle at position 1 for sshj, which this test reproduces, ahead
     * of `AndroidKeyStoreBCWorkaround`. An unqualified `Cipher.getInstance` defers provider selection
     * to `init`, and `Cipher` keeps only the first exception any provider raises. BouncyCastle accepts
     * the key: it declares no key attributes, so `supportsParameter` is true - calls `getEncoded()`,
     * gets `null` because Keystore keys are non-exportable, and dies in `KeyParameter.<init>`. Nobody
     * notices while the Keystore provider then succeeds; when it does not, that NPE is what the caller
     * sees, rethrown as-is.
     *
     * The cost was that `CredentialVault` decides a prompt is due by catching
     * `UserNotAuthenticatedException`, so the prompt became unreachable and windowed credentials were
     * unusable past their window.
     *
     * Reproducing it properly needs a lapsed biometric window and therefore a human, so a decrypt-only
     * key asked to encrypt stands in: it fails in the same `createOperation` call for a different
     * reason, which is enough to prove the Keystore's own `InvalidKeyException` arrives rather than
     * BouncyCastle's NPE.
     */
    @Test
    fun newEncryptCipher_reportsTheKeystoreFailure_notBouncyCastlesNpe() {
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        try {
            val vaultCrypto = VaultCrypto()

            val thrown =
                assertThrows(Throwable::class.java) {
                    vaultCrypto.newEncryptCipher(decryptOnlyKeyAlias())
                }

            assertTrue(
                "Expected the Keystore's own InvalidKeyException but got ${thrown.javaClass.name}: ${thrown.message} - " +
                        "a NullPointerException here means BouncyCastle intercepted the Keystore key again",
                thrown is InvalidKeyException,
            )
        } finally {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        }
    }

    /** A Keystore AES key that can only decrypt, so asking it to encrypt fails in `createOperation` with no human involved. */
    private fun decryptOnlyKeyAlias(): String {
        val alias = "test_decrypt_only"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                }
                .generateKey()
        }
        return alias
    }
}
