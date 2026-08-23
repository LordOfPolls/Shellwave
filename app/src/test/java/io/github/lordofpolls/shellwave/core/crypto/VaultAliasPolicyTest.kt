package io.github.lordofpolls.shellwave.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alias-selection and migration rules behind the double-biometric-prompt fix on ProxyJump
 * chains. A plain JVM test with no Keystore involved, which is why the rules were split out of
 * [CredentialVault] in the first place.
 */
class VaultAliasPolicyTest {

    // --- aliasForNewCredential -----------------------------------------------------------------

    @Test
    fun `new non-biometric credential gets the default alias`() {
        assertEquals(
            VaultCrypto.ALIAS_DEFAULT,
            VaultAliasPolicy.aliasForNewCredential(requireBiometric = false)
        )
    }

    @Test
    fun `new biometric credential gets the windowed alias, never the legacy one`() {
        val alias = VaultAliasPolicy.aliasForNewCredential(requireBiometric = true)
        assertEquals(VaultCrypto.ALIAS_BIOMETRIC_WINDOWED, alias)
        assertFalse(
            "a new write must never choose the legacy per-use alias",
            alias == VaultCrypto.ALIAS_BIOMETRIC
        )
    }

    // --- isBiometricAlias ------------------------------------------------------------------------

    @Test
    fun `default alias is not biometric`() {
        assertFalse(VaultAliasPolicy.isBiometricAlias(VaultCrypto.ALIAS_DEFAULT))
    }

    @Test
    fun `legacy biometric alias is biometric`() {
        assertTrue(VaultAliasPolicy.isBiometricAlias(VaultCrypto.ALIAS_BIOMETRIC))
    }

    @Test
    fun `windowed biometric alias is biometric`() {
        assertTrue(VaultAliasPolicy.isBiometricAlias(VaultCrypto.ALIAS_BIOMETRIC_WINDOWED))
    }

    @Test
    fun `null alias (keyboard-interactive marker rows) is not biometric`() {
        assertFalse(VaultAliasPolicy.isBiometricAlias(null))
    }

    @Test
    fun `an unrecognised alias string is not biometric`() {
        assertFalse(VaultAliasPolicy.isBiometricAlias("some_future_alias"))
    }

    // --- requiresPerUsePrompt --------------------------------------------------------------------

    @Test
    fun `only the legacy alias requires a per-use prompt`() {
        assertTrue(VaultAliasPolicy.requiresPerUsePrompt(VaultCrypto.ALIAS_BIOMETRIC))
        assertFalse(VaultAliasPolicy.requiresPerUsePrompt(VaultCrypto.ALIAS_BIOMETRIC_WINDOWED))
        assertFalse(VaultAliasPolicy.requiresPerUsePrompt(VaultCrypto.ALIAS_DEFAULT))
        assertFalse(VaultAliasPolicy.requiresPerUsePrompt(null))
    }

    // --- shouldOpportunisticallyReseal -----------------------------------------------------------

    @Test
    fun `a row on the legacy alias is due for re-seal`() {
        assertTrue(VaultAliasPolicy.shouldOpportunisticallyReseal(VaultCrypto.ALIAS_BIOMETRIC))
    }

    @Test
    fun `a row already on the windowed alias is not re-sealed again`() {
        assertFalse(VaultAliasPolicy.shouldOpportunisticallyReseal(VaultCrypto.ALIAS_BIOMETRIC_WINDOWED))
    }

    @Test
    fun `a non-biometric row is never re-sealed`() {
        assertFalse(VaultAliasPolicy.shouldOpportunisticallyReseal(VaultCrypto.ALIAS_DEFAULT))
    }

    @Test
    fun `a null alias (keyboard-interactive marker row) is never re-sealed`() {
        assertFalse(VaultAliasPolicy.shouldOpportunisticallyReseal(null))
    }

    // --- mixed-alias handling stays coherent across the whole policy -----------------------------

    @Test
    fun `aliases handed out for new credentials are stable under the predicates`() {
        // A row just created under aliasForNewCredential(true) must never itself be flagged as due
        // for re-seal - only a row still on the pre-existing legacy alias is.
        val freshBiometricAlias = VaultAliasPolicy.aliasForNewCredential(requireBiometric = true)
        assertTrue(VaultAliasPolicy.isBiometricAlias(freshBiometricAlias))
        assertFalse(VaultAliasPolicy.requiresPerUsePrompt(freshBiometricAlias))
        assertFalse(VaultAliasPolicy.shouldOpportunisticallyReseal(freshBiometricAlias))

        val freshDefaultAlias = VaultAliasPolicy.aliasForNewCredential(requireBiometric = false)
        assertFalse(VaultAliasPolicy.isBiometricAlias(freshDefaultAlias))
        assertFalse(VaultAliasPolicy.requiresPerUsePrompt(freshDefaultAlias))
        assertFalse(VaultAliasPolicy.shouldOpportunisticallyReseal(freshDefaultAlias))
    }
}
