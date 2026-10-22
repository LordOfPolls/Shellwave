package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.runtime.saveable.Saver
import io.github.lordofpolls.shellwave.core.crypto.CredentialType
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.decodeAgentKeyIds

/** Ids only: what survives recreation, so nothing sealed or secret ever reaches saved state. */
internal data class AgentConsentRequest(val hostId: Long, val keyIds: List<Long>)

// Fold and rotation keep the prompt, but a cold restore after process death drops it: every way
// out of the dialog connects, so one asked hours ago would log in somewhere nobody meant to.
private val PROCESS_NONCE = System.nanoTime()

internal fun agentConsentRequestSaver(nonce: Long = PROCESS_NONCE) =
    Saver<AgentConsentRequest?, LongArray>(
        save = { request -> request?.let { longArrayOf(nonce, it.hostId, *it.keyIds.toLongArray()) } },
        restore = { if (it[0] == nonce) AgentConsentRequest(it[1], it.drop(2)) else null },
    )

internal val TickedKeyIdsSaver =
    Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() })

/**
 * The keys that may be offered for [host] right now, out of [requestedIds]. Empty means no prompt:
 * a deleted host, forwarding switched off, a key since deleted or de-selected all fail closed rather
 * than forward something nobody agreed to.
 */
internal suspend fun offeredAgentKeys(
    host: HostEntity?,
    requestedIds: List<Long>,
    lookup: suspend (Long) -> CredentialEntity?,
): List<CredentialEntity> {
    if (host == null || !host.agentForwarding) return emptyList()
    val selected = decodeAgentKeyIds(host.agentKeyIdsJson)
    return requestedIds
        .filter { it in selected }
        .mapNotNull { lookup(it) }
        .filter { it.type == CredentialType.PRIVATE_KEY.name }
}
