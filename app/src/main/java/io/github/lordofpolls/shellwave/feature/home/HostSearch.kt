package io.github.lordofpolls.shellwave.feature.home

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity

/** Label, hostname or username; blank matches everything. */
fun hostMatches(host: HostEntity, query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    return (host.label?.contains(trimmed, ignoreCase = true) == true) ||
            host.hostname.contains(trimmed, ignoreCase = true) ||
            host.username.contains(trimmed, ignoreCase = true)
}
