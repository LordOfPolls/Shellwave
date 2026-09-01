package io.github.lordofpolls.shellwave.ssh

import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/** In dial order, farthest from the target first. A bastion authenticates like any other host. */
data class ProxyHop(
    val hostname: String,
    val port: Int,
    val username: String,
    val authMethod: AuthMethod
)

/** Not the final hop, which the caller owns. Empty when no jump is configured. */
class ProxyChainResources internal constructor(
    private val clients: List<SSHClient>,
    private val channels: List<DirectConnection>
) {
    /**
     * Deepest hop first, the reverse of build order: a link further out is still carrying traffic for
     * everything dialled through it. Call only once the caller's own final-hop client is down.
     * `runCatching` per resource, since one dead link is the common case and must not strand the rest.
     */
    fun disconnect() {
        for (i in channels.indices.reversed()) runCatching { channels[i].close() }
        for (i in clients.indices.reversed()) runCatching { clients[i].disconnect() }
    }
}

/**
 * sshj's own `SSHClient.connectVia(DirectConnection)` over a hand-rolled relay: each hop opens a
 * `direct-tcpip` channel through the previous hop's authenticated client, and [finalSsh] connects
 * the same way, so a chained connection is indistinguishable from a direct one afterwards.
 *
 * One [hostKeyVerifier] covers every hop including the target, so a bastion gets no relaxed policy.
 * A background run passes BackgroundKnownHostsVerifier, which refuses synchronously on an unknown
 * or changed key; this function does not distinguish the two.
 *
 * A failure partway through tears down everything already opened, so a caller that never sees a
 * successful return owns nothing but [finalSsh].
 */
internal fun connectChainAndAuthenticate(
    finalSsh: SSHClient,
    hops: List<ProxyHop>,
    targetHost: String,
    targetPort: Int,
    targetUsername: String,
    targetAuthMethod: AuthMethod,
    hostKeyVerifier: HostKeyVerifier,
): ProxyChainResources {
    if (hops.isEmpty()) {
        connectAndAuthenticate(
            finalSsh,
            targetHost,
            targetPort,
            targetUsername,
            targetAuthMethod,
            hostKeyVerifier
        )
        return ProxyChainResources(emptyList(), emptyList())
    }
    val clients = mutableListOf<SSHClient>()
    val channels = mutableListOf<DirectConnection>()
    try {
        var previous: SSHClient? = null
        for (hop in hops) {
            val client = SSHClient()
            val prev = previous
            if (prev == null) {
                connectAndAuthenticate(
                    client,
                    hop.hostname,
                    hop.port,
                    hop.username,
                    hop.authMethod,
                    hostKeyVerifier
                )
            } else {
                val channel = prev.newDirectConnection(hop.hostname, hop.port)
                channels += channel
                connectViaAndAuthenticate(
                    client,
                    channel,
                    hop.username,
                    hop.authMethod,
                    hostKeyVerifier
                )
            }
            clients += client
            previous = client
        }
        val finalChannel = previous!!.newDirectConnection(targetHost, targetPort)
        channels += finalChannel
        connectViaAndAuthenticate(
            finalSsh,
            finalChannel,
            targetUsername,
            targetAuthMethod,
            hostKeyVerifier
        )
        return ProxyChainResources(clients, channels)
    } catch (e: Exception) {
        for (i in channels.indices.reversed()) runCatching { channels[i].close() }
        for (i in clients.indices.reversed()) runCatching { clients[i].disconnect() }
        throw e
    }
}

/**
 * Every jump host that has to be dialled before [target], farthest first. Empty means connect
 * directly.
 *
 * A cycle and a dangling jump id both raise [IllegalStateException] with a message written for the
 * user, instead of looping forever or dereferencing a null. The dangling case cannot arise through
 * this app's own delete path - `HostEntity.proxyJumpHostId` is `onDelete = RESTRICT` - but a
 * restored backup or direct database surgery can still produce one.
 */
suspend fun resolveProxyChain(target: HostEntity, hostDao: HostDao): List<HostEntity> {
    val chain = mutableListOf<HostEntity>()
    val visited = mutableSetOf(target.id)
    var current = target
    while (true) {
        val jumpId = current.proxyJumpHostId ?: break
        check(visited.add(jumpId)) {
            "Proxy jump chain for ${target.label ?: target.hostname} has a cycle - it loops back to a host already earlier in the chain."
        }
        val jumpHost =
            hostDao.getById(jumpId)
                ?: error("The proxy jump host configured for ${target.label ?: target.hostname} no longer exists.")
        chain += jumpHost
        current = jumpHost
    }
    return chain.asReversed()
}

/**
 * [resolveProxyChain] with each hop's own credential resolved through the same [CredentialVault]
 * every host uses. [activity] is forwarded unchanged, so a biometric-gated hop fails a background
 * run the same way a biometric-gated target does. Resolving a keyboard-interactive hop succeeds
 * here, since it only returns a provider, because that refusal belongs in
 * [ScriptRunner.runCaptureBackground], which checks the hops and the target together.
 *
 * [trigger] is forwarded unchanged into every hop's [CredentialVault.resolve], exactly like [activity].
 */
suspend fun resolveProxyHops(
    target: HostEntity,
    hostDao: HostDao,
    credentialVault: CredentialVault,
    activity: FragmentActivity?,
    trigger: CredentialVault.TriggerAuth? = null,
): List<ProxyHop> =
    resolveProxyChain(target, hostDao).map { host ->
        ProxyHop(
            host.hostname,
            host.port,
            host.username,
            credentialVault.resolve(host.credentialId, activity, trigger)
        )
    }

suspend fun resolveConnectionSpec(
    host: HostEntity,
    hostDao: HostDao,
    credentialVault: CredentialVault,
    activity: FragmentActivity?,
    trigger: CredentialVault.TriggerAuth? = null,
): ConnectionSpec {
    val authMethod = credentialVault.resolve(host.credentialId, activity, trigger)
    val hops = resolveProxyHops(host, hostDao, credentialVault, activity, trigger)
    return ConnectionSpec(
        host.hostname,
        host.port,
        host.username,
        authMethod,
        hostId = host.id,
        resilientSession = host.resilientSession,
        proxyHops = hops,
    )
}
