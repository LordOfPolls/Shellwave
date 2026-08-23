package io.github.lordofpolls.shellwave.core.crypto

/**
 * Alias-selection and migration rules, split out of [CredentialVault] so they have a plain JVM test
 * and not only an instrumented one against a real Keystore. See VaultCrypto for what the three
 * aliases are.
 */
object VaultAliasPolicy {
    /**
     * [VaultCrypto.ALIAS_BIOMETRIC] is kept alive only to decrypt rows already sealed under it, so new
     * credentials go onto the windowed alias.
     */
    fun aliasForNewCredential(requireBiometric: Boolean): String =
        if (requireBiometric) VaultCrypto.ALIAS_BIOMETRIC_WINDOWED else VaultCrypto.ALIAS_DEFAULT

    /** `null` is a keyboard-interactive marker row, which has no alias. */
    fun isBiometricAlias(alias: String?): Boolean =
        alias == VaultCrypto.ALIAS_BIOMETRIC || alias == VaultCrypto.ALIAS_BIOMETRIC_WINDOWED

    /**
     * The legacy alias needs a `CryptoObject`-bound prompt per operation. The windowed one may succeed
     * with no prompt at all, which is the difference this predicate exists to draw.
     */
    fun requiresPerUsePrompt(alias: String?): Boolean = alias == VaultCrypto.ALIAS_BIOMETRIC

    /**
     * Re-sealing needs the plaintext and a satisfied biometric together, which a Room `Migration`
     * cannot arrange: it has no UI to prompt from. So rows move off the legacy alias one at a time, as
     * they happen to be decrypted, and there is no bulk migration.
     */
    fun shouldOpportunisticallyReseal(alias: String?): Boolean =
        alias == VaultCrypto.ALIAS_BIOMETRIC
}
