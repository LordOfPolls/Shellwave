package io.github.lordofpolls.shellwave.ssh

import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.feature.keys.credentialLabel

/**
 * Parsed once so the PEM does not outlive this call. The certificate is dropped on purpose: the
 * agent matches blobs by exact bytes and refuses `*-cert-v01@openssh.com`, so a certificate-form
 * public key would be advertised and then never signable.
 */
internal fun agentKeyFrom(auth: AuthMethod.PrivateKey, label: String): AgentKey {
    val provider = keyProviderFor(auth.copy(certificate = null))
    return AgentKey(provider.private, provider.public, label)
}

/** Needs the Activity, since unsealing may prompt; this is why keys cannot be resolved inside [SshConnection]. */
internal suspend fun resolveAgentKeys(
    keys: List<CredentialEntity>,
    credentialVault: CredentialVault,
    activity: FragmentActivity,
): List<AgentKey> =
    keys.map { agentKeyFrom(credentialVault.resolve(it.id, activity) as AuthMethod.PrivateKey, credentialLabel(it)) }
