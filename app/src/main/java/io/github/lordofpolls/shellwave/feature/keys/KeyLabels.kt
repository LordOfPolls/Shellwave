package io.github.lordofpolls.shellwave.feature.keys

import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity

internal fun credentialLabel(entity: CredentialEntity): String =
    entity.label ?: "${entity.type.lowercase().replace('_', ' ')} credential #${entity.id}"

/**
 * Algorithm, plus a flag for a certificate. Where [forwarded], the flag says the certificate is not
 * sent: the agent offers only the plain key, so claiming CA reach there would mislead.
 */
internal fun keyDescription(key: CredentialEntity, forwarded: Boolean = false): String =
    listOfNotNull(
        key.publicKeyText?.substringBefore(' '),
        (if (forwarded) "Certificate not forwarded" else "Certificate").takeIf { key.certificate != null },
    ).joinToString(" · ")
