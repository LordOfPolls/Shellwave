package io.github.lordofpolls.shellwave.feature.home

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity

private const val DEFAULT_PORT = 22
private const val DEFAULT_USERNAME = "root"

data class QuickConnectTarget(val username: String, val host: String, val port: Int)

/** Missing user defaults to `root`, missing port to 22. Null for blank or host-less input. */
fun parseQuickConnect(text: String): QuickConnectTarget? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    val atIndex = trimmed.indexOf('@')
    val username = if (atIndex >= 0) trimmed.substring(0, atIndex) else DEFAULT_USERNAME
    val rest = if (atIndex >= 0) trimmed.substring(atIndex + 1) else trimmed
    if (rest.isEmpty()) return null

    val colonIndex = rest.lastIndexOf(':')
    val host = if (colonIndex >= 0) rest.substring(0, colonIndex) else rest
    val port = if (colonIndex >= 0) rest.substring(colonIndex + 1).toIntOrNull()
        ?: DEFAULT_PORT else DEFAULT_PORT
    if (host.isEmpty()) return null

    return QuickConnectTarget(username.ifBlank { DEFAULT_USERNAME }, host, port)
}

/**
 * Lets quick connect offer a stored credential instead of asking for a password saved months ago.
 *
 * Matching is on the connection triple, never the label: a label is a nickname the user is free to
 * type into the box, and matching on it would connect somewhere other than what they typed.
 * Hostname compares case-insensitively because DNS is, username case-sensitively because POSIX is,
 * port exactly.
 *
 * All matches, never just the first: two saved hosts can point at one address with different
 * settings, so the caller has to let the user choose.
 */
fun savedHostsMatching(target: QuickConnectTarget, hosts: List<HostEntity>): List<HostEntity> =
    hosts.filter {
        it.hostname.equals(target.host, ignoreCase = true) &&
                it.port == target.port &&
                it.username == target.username
    }
