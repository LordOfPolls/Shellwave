package io.github.lordofpolls.shellwave.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.lordofpolls.shellwave.core.crypto.VaultCrypto.Companion.ALIAS_BIOMETRIC
import io.github.lordofpolls.shellwave.core.crypto.VaultCrypto.Companion.ALIAS_BIOMETRIC_WINDOWED
import io.github.lordofpolls.shellwave.core.crypto.VaultCrypto.Companion.ALIAS_DEFAULT
import io.github.lordofpolls.shellwave.core.crypto.VaultCrypto.Companion.BIOMETRIC_WINDOW_SECONDS
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Which stored credentials this is - kept as a plain string column in CredentialEntity, see its KDoc. */
enum class CredentialType { PASSWORD, PRIVATE_KEY, KEYBOARD_INTERACTIVE }

/** Ciphertext plus the GCM IV it was sealed with. Never reuse an IV - see `VaultCrypto.seal`. */
data class SealedBox(val iv: ByteArray, val ciphertext: ByteArray)

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/**
 * Name this on every [Cipher.getInstance] for a Keystore key. Unqualified, provider selection is
 * deferred to `init`, and `Cipher` keeps only the first exception any provider raised - which, with
 * BouncyCastle installed at position 1 for sshj, is BC's own NPE on a non-exportable key. The
 * [android.security.keystore.UserNotAuthenticatedException] behind it is what [CredentialVault]
 * needs to know a biometric prompt is due, and it never arrives.
 */
private const val ANDROID_KEYSTORE_CIPHER_PROVIDER = "AndroidKeyStoreBCWorkaround"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

/**
 * AES-256-GCM under hardware-backed Android Keystore, across three aliases.
 *
 * ALIAS_DEFAULT needs no authentication beyond an unlocked device
 * ([KeyGenParameterSpec.Builder.setUnlockedDeviceRequired]).
 *
 * [ALIAS_BIOMETRIC] requires a [android.hardware.biometrics.BiometricPrompt] per use, via a
 * zero-second `setUserAuthenticationRequired` validity. It is legacy: nothing new is sealed under
 * it (see `VaultAliasPolicy.aliasForNewCredential`), and it survives only to decrypt rows that
 * already are.
 *
 * [ALIAS_BIOMETRIC_WINDOWED] exists because that per-use validity cost one prompt per secret per
 * connect: an N-hop ProxyJump meant N prompts, as did a private key plus its passphrase. It sets
 * `setUserAuthenticationParameters(BIOMETRIC_WINDOW_SECONDS, AUTH_BIOMETRIC_STRONG)` instead: once
 * the user satisfies a `BIOMETRIC_STRONG` check, Gatekeeper lets any key on the device asking for
 * that authenticator run unprompted until the window lapses, so `Cipher.init` simply succeeds.
 * CredentialVault tries that plain path and only falls back to [BiometricGate] on
 * `UserNotAuthenticatedException`, which makes the first prompt of a connect cover the rest of it.
 *
 * Two consequences of a time-bound key are easy to get wrong. It cannot be driven by a
 * `CryptoObject` at all, and outside the window it is `Cipher.init` rather than `doFinal` that
 * throws - so there is no cipher to bind a prompt to, and the fallback has to prompt bare and then
 * retry the whole operation, the reverse of [ALIAS_BIOMETRIC]'s shape. The other is the trade-off
 * the user accepted when asking for this: during the window any secret under this alias unlocks,
 * not only the one they meant. Keystore scopes the keys to this app, and [BIOMETRIC_WINDOW_SECONDS]
 * bounds the rest. Labelling each prompt with its secret and merging key-plus-passphrase into one
 * blob were both considered; neither addresses the proxy-chain case that prompted the change.
 *
 * keystoreAlias records which alias sealed a row, fixed at creation - authentication parameters
 * cannot be altered on an existing Keystore key, so moving a row means decrypting under its
 * recorded alias and re-encrypting under another. CredentialVault does that opportunistically when
 * a legacy row next decrypts. A bulk pass would need a prompt per row, and could not run inside a
 * Room `Migration` anyway (no activity, no UI, executes during `openHelper.writableDatabase`). So
 * mixed aliases are a permanent state, not a transition, and alias-aware code has to honour what a
 * row records.
 *
 * This class holds and uses keys; when biometric confirmation is required is decided by whoever
 * calls [newEncryptCipher]/[newDecryptCipher] with a biometric alias.
 */
@Singleton
class VaultCrypto @Inject constructor() {

    // Lazy so JVM tests can construct this without an AndroidKeyStore; only loading a key needs one.
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    /**
     * Generates the key for [alias] if it doesn't already exist in the Keystore. Idempotent. [alias]
     * itself decides the auth-validity window when [requireBiometric] is set:
     * [ALIAS_BIOMETRIC_WINDOWED] gets [BIOMETRIC_WINDOW_SECONDS], every other biometric alias (i.e. the
     * legacy `ALIAS_BIOMETRIC`) gets the original zero-second "every use" validity - see this class's
     * doc for what that difference costs and buys.
     */
    fun ensureKey(alias: String, requireBiometric: Boolean) {
        if (keyStore.containsAlias(alias)) return
        val builder =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
        if (requireBiometric) {
            val validitySeconds =
                if (alias == ALIAS_BIOMETRIC_WINDOWED) BIOMETRIC_WINDOW_SECONDS else 0
            builder
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    validitySeconds,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
        }
        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    private fun secretKey(alias: String): SecretKey = keyStore.getKey(alias, null) as SecretKey

    /**
     * For [ALIAS_BIOMETRIC], `doFinal` throws until the cipher has been through BiometricGate. For
     * [ALIAS_BIOMETRIC_WINDOWED] it is this call that throws
     * [android.security.keystore.UserNotAuthenticatedException] past the window, since a time-bound key
     * will not initialise before the user authenticates.
     */
    fun newEncryptCipher(alias: String): Cipher =
        Cipher.getInstance(TRANSFORMATION, ANDROID_KEYSTORE_CIPHER_PROVIDER)
            .apply { init(Cipher.ENCRYPT_MODE, secretKey(alias)) }

    /** A fresh `Cipher` in decrypt mode for the given [iv]. */
    fun newDecryptCipher(alias: String, iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION, ANDROID_KEYSTORE_CIPHER_PROVIDER).apply {
            init(Cipher.DECRYPT_MODE, secretKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
        }

    /**
     * Encrypts [plaintext] under [alias]. Pass an already-authenticated [cipher] for [ALIAS_BIOMETRIC]
     * (from [BiometricGate.authenticate]); omit it for [ALIAS_DEFAULT], where a fresh cipher is created
     * and used immediately. The IV is whatever the Keystore generated for this call - GCM must never
     * reuse an IV under the same key, and [newEncryptCipher] generates a new random one on every
     * `init`.
     */
    fun seal(alias: String, plaintext: ByteArray, cipher: Cipher? = null): SealedBox {
        val c = cipher ?: newEncryptCipher(alias)
        val ciphertext = c.doFinal(plaintext)
        return SealedBox(iv = c.iv, ciphertext = ciphertext)
    }

    /** Inverse of [seal]. */
    fun open(alias: String, sealed: SealedBox, cipher: Cipher? = null): ByteArray {
        val c = cipher ?: newDecryptCipher(alias, sealed.iv)
        return c.doFinal(sealed.ciphertext)
    }

    companion object {
        const val ALIAS_DEFAULT = "vault_default"
        const val ALIAS_BIOMETRIC = "vault_biometric"
        const val ALIAS_BIOMETRIC_WINDOWED = "vault_biometric_windowed"

        /** How long one authentication keeps [ALIAS_BIOMETRIC_WINDOWED] usable. This bounds the
         * trade-off described in the class doc, so it is not a free knob. */
        const val BIOMETRIC_WINDOW_SECONDS = 30
    }
}
