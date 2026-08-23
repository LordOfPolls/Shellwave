package io.github.lordofpolls.shellwave.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

enum class Reachability { UP, DOWN, UNKNOWN }

/**
 * How long a single TCP connect may take before the host counts as [Reachability.DOWN]. Under the
 * shortest ReachabilityInterval, so a pass can never overrun the next one.
 */
private const val PROBE_TIMEOUT_MS = 3_000

/**
 * "Is sshd accepting connections on this host right now?", asked periodically while the app is in
 * the foreground and never otherwise.
 *
 * The only part of the app that touches the network without the user asking for that particular
 * connection, so its shape is all constraint. Off by default, and off on metered networks by
 * default. [start]/[stop] hang off `ProcessLifecycleOwner`, not screen composition, which would
 * keep probing behind a dialog and stop while scrolled off, and not `WorkManager`, which would
 * probe with the app closed. [states] lives in memory and is cleared on [stop]: a cached "UP"
 * outlives everything that made it true. Nothing is sent, only a TCP connect and an immediate
 * close, with no banner read, no credential touched and no key exchange.
 *
 * TCP and not ICMP because Android cannot send ICMP without root, and `InetAddress.isReachable`
 * quietly falls back to TCP port 7, which is closed nearly everywhere: it would call every host
 * down and be believed.
 *
 * Proxied hosts are not probed; see [hostsToProbe].
 */
@Singleton
class HostReachabilityProbe
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val hostDao: HostDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _states = MutableStateFlow<Map<Long, Reachability>>(emptyMap())

    val states: StateFlow<Map<Long, Reachability>> = _states.asStateFlow()

    /** Restarts cleanly, so it serves as both the `ON_START` hook and a settings-changed callback. */
    fun start() {
        stop()
        if (!ReachabilityPreferences.isEnabled(context)) return
        job =
            scope.launch {
                while (isActive) {
                    probeOnce()
                    delay(ReachabilityPreferences.interval(context).millis)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        _states.value = emptyMap()
    }

    private suspend fun probeOnce() {
        if (!networkAllowed()) {
            // Not an error, and not "everything is down": the honest answer is that we do not know.
            _states.value = emptyMap()
            return
        }
        val hosts = hostsToProbe(hostDao.observeAll().first())
        val results =
            coroutineScope {
                hosts.map { host -> async { host.id to probe(host) } }.awaitAll()
            }
        _states.value = results.toMap()
    }

    private suspend fun probe(host: HostEntity): Reachability =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use {
                    it.connect(
                        InetSocketAddress(host.hostname, host.port),
                        PROBE_TIMEOUT_MS
                    )
                }
            }.fold({ Reachability.UP }, { Reachability.DOWN })
        }

    /**
     * Absent connectivity information counts as "no". A wrong `true` costs the user data charges they
     * did not agree to; a wrong `false` costs an indicator that says `-`.
     */
    private fun networkAllowed(): Boolean {
        if (ReachabilityPreferences.allowsMetered(context)) return true
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * A host with a HostEntity.proxyJumpHostId is not reachable from the phone at all, that being the
 * entire reason it has a jump host, so a direct connect would fail every time. An indicator saying
 * `DOWN` about a host that is working perfectly teaches the user to stop reading it.
 */
fun hostsToProbe(hosts: List<HostEntity>): List<HostEntity> =
    hosts.filter { it.proxyJumpHostId == null }
