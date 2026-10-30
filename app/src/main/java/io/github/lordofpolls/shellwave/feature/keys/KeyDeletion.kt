package io.github.lordofpolls.shellwave.feature.keys

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity

/**
 * `null` when the delete may proceed, else a message naming the hosts using the key.
 *
 * `HostEntity.credentialId` is a `RESTRICT` foreign key, so the database would refuse with a raw
 * `SQLiteConstraintException`. A block, never a repair: reassigning or cascading would silently
 * change how those hosts authenticate.
 */
fun keyDeleteBlockReason(keyName: String, usedBy: List<HostEntity>): String? {
    if (usedBy.isEmpty()) return null
    val names = usedBy.joinToString(", ") { it.label ?: it.hostname }
    return "Can't delete $keyName: still used by $names. Switch the host's login to another key first."
}

/**
 * The same block when only a count is to hand: the authoritative database re-check can say a key is
 * in use at a moment when the observed host list is still empty and so cannot name anything.
 */
fun keyDeleteBlockReason(keyName: String, usedByCount: Int): String? {
    if (usedByCount <= 0) return null
    val hosts = if (usedByCount == 1) "host" else "hosts"
    return "Can't delete $keyName: still used by $usedByCount $hosts. Switch the host's login to another key first."
}

/**
 * Never a block: a forwarding id that outlives its key fails closed, because it is simply not
 * offered at connect and ids are never reused.
 */
fun keyDeleteForwardingWarning(forwardedBy: List<HostEntity>): String? {
    if (forwardedBy.isEmpty()) return null
    val names = forwardedBy.joinToString(", ") { it.label ?: it.hostname }
    return "Forwarded to $names. They'll stop offering it."
}
