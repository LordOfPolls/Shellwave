package io.github.lordofpolls.shellwave.core.crypto

import android.security.keystore.UserNotAuthenticatedException
import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.ssh.AuthMethod
import io.github.lordofpolls.shellwave.ssh.KeyboardInteractiveGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `ConfigImporter` creates rows with the sealed columns empty, because the export deliberately
 * leaves the secret behind. Keyboard-interactive has none by design and is always ready.
 */
private fun CredentialEntity.hasSealedSecret(): Boolean =
    CredentialType.valueOf(type) == CredentialType.KEYBOARD_INTERACTIVE ||
            (keystoreAlias != null && secretIv != null && secretCiphertext != null)

/**
 * The one place that turns a CredentialEntity row into a live [AuthMethod] and back, so host and
 * home don't each reimplement "which alias, when does biometric run, how do the two secret fields
 * map onto a credential type". Not a repository layer over Room in general - `CredentialDao` is
 * used directly everywhere else; this exists because sealing/unsealing is one job split across
 * [VaultCrypto], [BiometricGate] and [KeyboardInteractiveGate].
 */
@Singleton
open class CredentialVault
@Inject
constructor(
    private val vaultCrypto: VaultCrypto,
    private val credentialDao: CredentialDao,
    private val biometricGate: BiometricGate,
    private val keyboardInteractiveGate: KeyboardInteractiveGate,
) {
    /** [type]/[publicKeyText] only - no secret material, so this needs no [VaultCrypto]/biometric round trip. */
    data class CredentialSummary(
        val type: CredentialType,
        val publicKeyText: String?,
        /** False for a row that arrived through a config import - see [hasSealedSecret]. */
        val hasStoredSecret: Boolean,
        val requireBiometric: Boolean,
        val certificate: String?,
    )

    data class TriggerAuth(val scriptId: Long, val token: String)

    private val triggerAuthStash = TriggerAuthStash()

    /**
     * Lets the edit-host screen know what kind of credential a host already has without decrypting it.
     * Without this, reopening an edit screen showed the "Password" radio regardless of the host's
     * actual stored auth method.
     */
    suspend fun describe(credentialId: Long): CredentialSummary? {
        val credential = credentialDao.getById(credentialId) ?: return null
        return CredentialSummary(
            CredentialType.valueOf(credential.type),
            credential.publicKeyText,
            credential.hasSealedSecret(),
            VaultAliasPolicy.isBiometricAlias(credential.keystoreAlias),
            credential.certificate,
        )
    }

    /** Updates only the plaintext `certificate` column - no re-seal, since it never went through [VaultCrypto]. */
    suspend fun setCertificate(credentialId: Long, certificate: String?) {
        credentialDao.setCertificate(credentialId, certificate)
    }

    suspend fun storePassword(
        password: String,
        label: String?,
        requireBiometric: Boolean,
        activity: FragmentActivity?
    ): Long {
        val alias = aliasFor(requireBiometric)
        val sealed = encrypt(alias, password, activity)
        return credentialDao.insert(
            CredentialEntity(
                type = CredentialType.PASSWORD.name,
                label = label,
                keystoreAlias = alias,
                secretIv = sealed.iv,
                secretCiphertext = sealed.ciphertext,
                passphraseIv = null,
                passphraseCiphertext = null,
                publicKeyText = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun storePrivateKey(
        privateKeyPem: String,
        passphrase: String?,
        publicKeyText: String,
        label: String?,
        requireBiometric: Boolean,
        activity: FragmentActivity?,
        certificate: String? = null,
    ): Long {
        val alias = aliasFor(requireBiometric)
        val sealedKey = encrypt(alias, privateKeyPem, activity)
        val sealedPassphrase = passphrase?.let { encrypt(alias, it, activity) }
        return credentialDao.insert(
            CredentialEntity(
                type = CredentialType.PRIVATE_KEY.name,
                label = label,
                keystoreAlias = alias,
                secretIv = sealedKey.iv,
                secretCiphertext = sealedKey.ciphertext,
                passphraseIv = sealedPassphrase?.iv,
                passphraseCiphertext = sealedPassphrase?.ciphertext,
                publicKeyText = publicKeyText,
                createdAt = System.currentTimeMillis(),
                certificate = certificate,
            ),
        )
    }

    /** No secret to seal - the server prompts live at connect time via [KeyboardInteractiveGate]. */
    suspend fun storeKeyboardInteractive(label: String?): Long =
        credentialDao.insert(
            CredentialEntity(
                type = CredentialType.KEYBOARD_INTERACTIVE.name,
                label = label,
                keystoreAlias = null,
                secretIv = null,
                secretCiphertext = null,
                passphraseIv = null,
                passphraseCiphertext = null,
                publicKeyText = null,
                createdAt = System.currentTimeMillis(),
            ),
        )

    /**
     * [activity] is required only if the credential was sealed under VaultCrypto.ALIAS_BIOMETRIC or
     * [VaultCrypto.ALIAS_BIOMETRIC_WINDOWED] - a `null` activity refuses immediately with a clear
     * message for either, before any Keystore call is even attempted, which keeps a background trigger
     * (ScriptTriggerService, which always passes `activity = null`) from ever silently decrypting a
     * biometric-gated credential merely because some other, foreground part of the app happened to open
     * the [VaultCrypto.ALIAS_BIOMETRIC_WINDOWED] window a moment earlier: see [decryptWindowed].
     *
     * On a successful decrypt of a row still sealed under the legacy `VaultCrypto.ALIAS_BIOMETRIC`,
     * this opportunistically re-seals it onto [VaultCrypto.ALIAS_BIOMETRIC_WINDOWED] before returning:
     * see [resealOntoWindowedAliasIfDue].
     */
    open suspend fun resolve(
        credentialId: Long,
        activity: FragmentActivity?,
        trigger: TriggerAuth? = null,
    ): AuthMethod {
        if (activity == null && trigger != null) {
            triggerAuthStash.take(trigger.token, trigger.scriptId, credentialId)?.let { return it }
        }
        val credential = credentialDao.getById(credentialId) ?: error("No credential $credentialId")
        // Before the `!!`s below, which a secret-less imported row would otherwise turn into a bare
        // NPE at connect time.
        if (!credential.hasSealedSecret()) {
            error(
                "This credential has no stored secret - it came from an imported configuration. " +
                        "Edit the host and enter its password or key again.",
            )
        }
        return when (CredentialType.valueOf(credential.type)) {
            CredentialType.PASSWORD -> {
                val password = decrypt(
                    credential.keystoreAlias!!,
                    credential.secretIv!!,
                    credential.secretCiphertext!!,
                    activity
                )
                resealOntoWindowedAliasIfDue(credential, password, passphrase = null)
                AuthMethod.Password(password)
            }

            CredentialType.PRIVATE_KEY -> {
                val alias = credential.keystoreAlias!!
                val pem =
                    decrypt(alias, credential.secretIv!!, credential.secretCiphertext!!, activity)
                val passphrase =
                    if (credential.passphraseIv != null && credential.passphraseCiphertext != null) {
                        decrypt(
                            alias,
                            credential.passphraseIv,
                            credential.passphraseCiphertext,
                            activity
                        )
                    } else {
                        null
                    }
                resealOntoWindowedAliasIfDue(credential, pem, passphrase)
                AuthMethod.PrivateKey(pem, passphrase, credential.certificate)
            }

            CredentialType.KEYBOARD_INTERACTIVE -> AuthMethod.KeyboardInteractive(
                keyboardInteractiveGate.newProvider()
            )
        }
    }

    suspend fun resolveAndStash(
        trigger: TriggerAuth,
        credentialId: Long,
        activity: FragmentActivity,
    ): AuthMethod {
        val authMethod = resolve(credentialId, activity)
        triggerAuthStash.put(trigger.token, trigger.scriptId, credentialId, authMethod)
        return authMethod
    }

    /** Every new biometric-gated credential is sealed under the windowed alias - see VaultAliasPolicy.aliasForNewCredential. */
    private fun aliasFor(requireBiometric: Boolean): String {
        val alias = VaultAliasPolicy.aliasForNewCredential(requireBiometric)
        vaultCrypto.ensureKey(alias, requireBiometric)
        return alias
    }

    /**
     * [alias] is always [VaultCrypto.ALIAS_DEFAULT] or `VaultCrypto.ALIAS_BIOMETRIC_WINDOWED`, since
     * [aliasFor] never hands out the legacy one for a new write, so there is no `CryptoObject` path
     * here. The windowed alias tries the plain path first: a window opened by an earlier prompt in the
     * same save or connect removes the second prompt for a private key and its passphrase. Only
     * `UserNotAuthenticatedException` falls back to [BiometricGate].
     *
     * That fallback prompts bare and retries the seal, instead of authenticating a pre-built `Cipher`
     * the way [decryptWithPrompt] does, because a time-bound key will not initialise before the user
     * authenticates - the throw comes from [VaultCrypto.newEncryptCipher], never `doFinal`. Building a
     * cipher inside the handler raises the same exception again, which left the prompt unreachable when
     * it shipped that way.
     *
     * [activity] is required before the Keystore is touched, as in [decryptWindowed].
     */
    private suspend fun encrypt(
        alias: String,
        plaintext: String,
        activity: FragmentActivity?
    ): SealedBox {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        if (!VaultAliasPolicy.isBiometricAlias(alias)) {
            return withContext(Dispatchers.IO) { vaultCrypto.seal(alias, bytes) }
        }
        val fragmentActivity =
            activity ?: error("A biometric-protected credential needs an activity to prompt from")
        return try {
            withContext(Dispatchers.IO) { vaultCrypto.seal(alias, bytes) }
        } catch (e: UserNotAuthenticatedException) {
            biometricGate.authenticate(fragmentActivity, "Unlock vault to save credential")
            withContext(Dispatchers.IO) { vaultCrypto.seal(alias, bytes) }
        }
    }

    private suspend fun decrypt(
        alias: String,
        iv: ByteArray,
        ciphertext: ByteArray,
        activity: FragmentActivity?
    ): String {
        val sealed = SealedBox(iv, ciphertext)
        val plaintext =
            when {
                !VaultAliasPolicy.isBiometricAlias(alias) -> withContext(Dispatchers.IO) {
                    vaultCrypto.open(
                        alias,
                        sealed
                    )
                }

                VaultAliasPolicy.requiresPerUsePrompt(alias) -> decryptWithPrompt(
                    alias,
                    sealed,
                    activity
                )

                else -> decryptWindowed(alias, sealed, activity)
            }
        return plaintext.toString(Charsets.UTF_8)
    }

    /**
     * The windowed alias's fast path. [activity] is checked, and the call refused, before the Keystore
     * is touched at all; whether or not the window happens to be open. Otherwise a background trigger
     * could succeed here purely on timing, because some unrelated foreground operation opened the
     * window moments earlier, and silently decrypt a biometric credential it was never entitled to.
     *
     * The fallback is not [decryptWithPrompt]: a time-bound key cannot be bound to a `CryptoObject`, so
     * VaultCrypto.newDecryptCipher throws the same `UserNotAuthenticatedException` that got here and
     * the prompt never appears. Prompt first, then retry the open.
     */
    private suspend fun decryptWindowed(
        alias: String,
        sealed: SealedBox,
        activity: FragmentActivity?
    ): ByteArray {
        if (activity == null) error("A biometric-protected credential needs an activity to prompt from")
        return try {
            withContext(Dispatchers.IO) { vaultCrypto.open(alias, sealed) }
        } catch (e: UserNotAuthenticatedException) {
            biometricGate.authenticate(activity, "Unlock vault to connect")
            withContext(Dispatchers.IO) { vaultCrypto.open(alias, sealed) }
        }
    }

    /** Always shows a [BiometricGate] prompt - the legacy [VaultCrypto.ALIAS_BIOMETRIC] path. */
    private suspend fun decryptWithPrompt(
        alias: String,
        sealed: SealedBox,
        activity: FragmentActivity?
    ): ByteArray {
        val fragmentActivity =
            activity ?: error("A biometric-protected credential needs an activity to prompt from")
        val cipher = vaultCrypto.newDecryptCipher(alias, sealed.iv)
        val authenticated =
            biometricGate.authenticate(fragmentActivity, cipher, "Unlock vault to connect")
        return vaultCrypto.open(alias, sealed, authenticated)
    }

    /**
     * Opportunistic migration off the legacy alias, and the one moment the app holds both a row's
     * plaintext - just decrypted in [resolve] - and a fresh `BIOMETRIC_STRONG` authentication. The same
     * authentication that unlocked the auth-per-use key opens the windowed alias's window, since
     * Gatekeeper tracks the last authentication per device and not per key, so the re-seal goes through
     * `VaultCrypto.seal`'s plain path with no second prompt.
     *
     * Best-effort: [runCatching] swallows any failure and leaves the row for the next decrypt.
     * Housekeeping must not turn a successful connect into a failed one.
     */
    private suspend fun resealOntoWindowedAliasIfDue(
        credential: CredentialEntity,
        secret: String,
        passphrase: String?
    ) {
        if (!VaultAliasPolicy.shouldOpportunisticallyReseal(credential.keystoreAlias)) return
        runCatching {
            val newAlias = VaultCrypto.ALIAS_BIOMETRIC_WINDOWED
            vaultCrypto.ensureKey(newAlias, requireBiometric = true)
            val sealedSecret = withContext(Dispatchers.IO) {
                vaultCrypto.seal(
                    newAlias,
                    secret.toByteArray(Charsets.UTF_8)
                )
            }
            val sealedPassphrase = passphrase?.let {
                withContext(Dispatchers.IO) {
                    vaultCrypto.seal(
                        newAlias,
                        it.toByteArray(Charsets.UTF_8)
                    )
                }
            }
            credentialDao.update(
                credential.copy(
                    keystoreAlias = newAlias,
                    secretIv = sealedSecret.iv,
                    secretCiphertext = sealedSecret.ciphertext,
                    passphraseIv = sealedPassphrase?.iv ?: credential.passphraseIv,
                    passphraseCiphertext = sealedPassphrase?.ciphertext
                        ?: credential.passphraseCiphertext,
                ),
            )
        }
    }
}
