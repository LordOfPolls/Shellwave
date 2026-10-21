package io.github.lordofpolls.shellwave.feature.host

import io.github.lordofpolls.shellwave.core.crypto.CredentialType
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity

/**
 * `null` when the delete may proceed, else a message naming the dependents.
 *
 * `proxyJumpHostId` is a `RESTRICT` foreign key, so the database would refuse with a raw
 * `SQLiteConstraintException`. Checking first turns that into something a user can read.
 *
 * A block, never a repair: detaching the dependents or cascading would silently turn a bastioned
 * host into a direct connection, which either fails outright or quietly connects somewhere the user
 * never intended.
 */
fun hostDeleteBlockReason(host: HostEntity, dependents: List<HostEntity>): String? {
    if (dependents.isEmpty()) return null
    val names = dependents.joinToString(", ") { it.label ?: it.hostname }
    val subject = if (dependents.size == 1) "it" else "them"
    return "Can't delete ${host.label ?: host.hostname}: still used as a jump host by $names. Update $subject to connect directly (or through another host) first."
}

suspend fun deleteHostWithCleanup(
    host: HostEntity,
    hostDao: HostDao,
    credentialDao: CredentialDao,
): String? {
    val blockReason = hostDeleteBlockReason(host, hostDao.getProxyJumpDependents(host.id))
    if (blockReason != null) return blockReason
    hostDao.delete(host)
    // ~/.ssh/config import can attach one credential to several hosts, so this row is not
    // always this host's alone to delete.
    if (hostDao.countOtherHostsUsingCredential(host.credentialId, host.id) == 0) {
        // A key is a user-managed asset with its own screen: it may be registered with GitHub or
        // selected for forwarding, so deleting a host must not destroy it. Keeping it is also what
        // stops a future agent key selection from dangling.
        credentialDao.getById(host.credentialId)
            ?.takeIf { it.type != CredentialType.PRIVATE_KEY.name }
            ?.let { credentialDao.delete(it) }
    }
    return null
}
