package io.github.lordofpolls.shellwave.ssh

/**
 * [hostId] is null for an unsaved quick-connect, and [resilientSession]/[proxyHops] are
 * correspondingly off and empty, there being no saved host row to carry either.
 *
 * [proxyHops] arrives already resolved, chain walked and each hop's credential decrypted, by
 * whichever screen built the spec. That resolution needs an `Activity` for a biometric-gated hop
 * credential, so it cannot happen down in SessionManager or [SshConnection].
 */
data class ConnectionSpec(
    val hostname: String,
    val port: Int,
    val username: String,
    val authMethod: AuthMethod,
    val hostId: Long?,
    val resilientSession: Boolean = false,
    val proxyHops: List<ProxyHop> = emptyList(),
)
