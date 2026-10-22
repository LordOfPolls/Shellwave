package io.github.lordofpolls.shellwave.feature.host

internal sealed interface CredentialChoice {
    data class Existing(val id: Long) : CredentialChoice
    data class Stored(val id: Long) : CredentialChoice
    data object SealNew : CredentialChoice
}

/** [certificateTargetId] is the credential row to write the certificate to, or null for none. */
internal data class CredentialSavePlan(val credential: CredentialChoice, val certificateTargetId: Long?)

/**
 * Null when the inputs cannot be acted on: a stored key with nothing picked, or an unchanged
 * credential with no existing host to take it from.
 *
 * A certificate only belongs on a private key row, so it is never written for a password or
 * keyboard-interactive credential even if one was loaded under Import key before switching back.
 */
internal fun planCredentialSave(
    authKind: AuthKind,
    storedKeyId: Long?,
    keepExistingCredential: Boolean,
    certificateTouched: Boolean,
    existingCredentialId: Long?,
): CredentialSavePlan? =
    when {
        authKind == AuthKind.STORED_KEY ->
            storedKeyId?.let {
                CredentialSavePlan(CredentialChoice.Stored(it), it.takeIf { certificateTouched })
            }

        keepExistingCredential ->
            existingCredentialId?.let {
                CredentialSavePlan(
                    CredentialChoice.Existing(it),
                    it.takeIf { certificateTouched && authKind == AuthKind.IMPORT_KEY },
                )
            }

        else -> CredentialSavePlan(CredentialChoice.SealNew, null)
    }
