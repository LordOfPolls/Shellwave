package io.github.lordofpolls.shellwave.core.crypto

import io.github.lordofpolls.shellwave.ssh.AuthMethod

internal class TriggerAuthStash(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private class Entry(
        val scriptId: Long,
        val authMethod: AuthMethod,
        val expiresAt: Long,
        var remainingUses: Int,
    )

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun put(token: String, scriptId: Long, credentialId: Long, authMethod: AuthMethod) {
        val key = key(token, credentialId)
        val existing = entries[key]
        if (existing != null && existing.scriptId == scriptId && now() < existing.expiresAt) {
            existing.remainingUses++
        } else {
            entries[key] = Entry(scriptId, authMethod, now() + ttlMs, remainingUses = 1)
        }
    }

    @Synchronized
    fun take(token: String, scriptId: Long, credentialId: Long): AuthMethod? {
        val key = key(token, credentialId)
        val entry = entries[key] ?: return null
        entry.remainingUses--
        if (entry.remainingUses <= 0) entries.remove(key)
        if (entry.scriptId != scriptId) return null
        if (now() >= entry.expiresAt) return null
        return entry.authMethod
    }

    @Synchronized
    fun purgeExpired() {
        val cutoff = now()
        entries.values.removeAll { it.expiresAt <= cutoff }
    }

    private fun key(token: String, credentialId: Long) = "$token:$credentialId"

    companion object {
        const val DEFAULT_TTL_MS = 10_000L
    }
}
