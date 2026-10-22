package io.github.lordofpolls.shellwave.feature.keys

import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.decodeAgentKeyIds

/** A switched-off host keeps its picked ids but offers nothing at connect, so it is not forwarding. */
fun hostsForwardingKey(keyId: Long, hosts: List<HostEntity>): List<HostEntity> =
    hosts.filter { it.agentForwarding && keyId in decodeAgentKeyIds(it.agentKeyIdsJson) }

fun keyUsageSummary(keyId: Long, hosts: List<HostEntity>): String {
    val login = hosts.count { it.credentialId == keyId }
    val forwarded = hostsForwardingKey(keyId, hosts).size
    return listOfNotNull(
        "Login for ${hostCount(login)}".takeIf { login > 0 },
        "Forwarded to ${hostCount(forwarded)}".takeIf { forwarded > 0 },
    ).joinToString(" · ").ifEmpty { "Unused" }
}

private fun hostCount(n: Int) = "$n ${if (n == 1) "host" else "hosts"}"

fun unusedKeys(keys: List<CredentialEntity>, hosts: List<HostEntity>): List<CredentialEntity> =
    keys.filter { key -> hosts.none { it.credentialId == key.id } && hostsForwardingKey(key.id, hosts).isEmpty() }
