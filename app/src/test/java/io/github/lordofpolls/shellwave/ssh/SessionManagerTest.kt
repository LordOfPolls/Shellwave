package io.github.lordofpolls.shellwave.ssh

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.service.SessionAlerts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-memory stand-in for the handful of [SharedPreferences] calls SessionManager's dependencies make. */
private class FakeSharedPreferences : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = ConcurrentHashMap<String, Any?>()
        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { pending[key!!] = values }

        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
        override fun remove(key: String?) = apply { pending.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit(): Boolean {
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}

/**
 * Just enough of [Context] for SessionManager's own code path: a working prefs store (used by
 * SupportPreferences/AppearancePreferences), a [filesDir] for the known_hosts file, and inert
 * foreground-service starts. Everything else on the real [ContextWrapper] delegates to a `null`
 * base and would NPE if this test ever exercised it.
 */
private class FakeContext(private val tempDir: File) : ContextWrapper(null) {
    private val prefs = FakeSharedPreferences()
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getFilesDir(): File = tempDir
    override fun getApplicationContext(): Context = this
    override fun startForegroundService(service: Intent): ComponentName? = null
    override fun startService(service: Intent): ComponentName? = null
}

/**
 * A no-op stand-in for the real [SessionAlerts]: the real one posts through `Dispatchers.Main`,
 * which has no working implementation on the plain JVM this test runs on (no Robolectric, no
 * kotlinx-coroutines-test in this project - see SessionAlerts.kt's `open` markers).
 */
private class NoopSessionAlerts(context: Context) : SessionAlerts(context) {
    override fun dropped(sessionId: Long, label: String) = Unit
    override fun reconnected(sessionId: Long, label: String) = Unit
    override fun forget(sessionId: Long) = Unit
}

private class EmptyHostDao : HostDao {
    override fun observeAll(): Flow<List<HostEntity>> = TODO("not used")
    override fun observeRecents(limit: Int): Flow<List<HostEntity>> = TODO("not used")
    override suspend fun getById(id: Long): HostEntity? = TODO("not used - tests use hostId = null")
    override suspend fun insert(host: HostEntity): Long = TODO("not used")
    override suspend fun update(host: HostEntity) = TODO("not used")
    override suspend fun delete(host: HostEntity) = TODO("not used")
    override suspend fun getProxyJumpDependents(hostId: Long): List<HostEntity> = TODO("not used")
    override suspend fun countOtherHostsUsingCredential(credentialId: Long, excludeHostId: Long): Int =
        TODO("not used")

    override suspend fun touchLastConnected(id: Long, timestamp: Long) = TODO("not used")
}

private class FakeTerminalProfileDao : TerminalProfileDao {
    override suspend fun getAll(): List<TerminalProfileEntity> = TODO("not used")
    override suspend fun getDefault(): TerminalProfileEntity? = null // falls back to DEFAULT_TERMINAL_PROFILE
    override fun observeDefault(): Flow<TerminalProfileEntity?> = TODO("not used")
    override suspend fun getById(id: Long): TerminalProfileEntity? = TODO("not used")
    override suspend fun insert(profile: TerminalProfileEntity): Long = TODO("not used")
    override suspend fun update(profile: TerminalProfileEntity) = TODO("not used")
    override suspend fun delete(profile: TerminalProfileEntity) = TODO("not used")
}

private class FakeColorSchemeDao : ColorSchemeDao {
    override suspend fun getAll(): List<ColorSchemeEntity> = TODO("not used")
    override suspend fun getDefault(): ColorSchemeEntity? = null // falls back to DEFAULT_COLOR_SCHEME
    override fun observeDefault(): Flow<ColorSchemeEntity?> = TODO("not used")
    override suspend fun getById(id: Long): ColorSchemeEntity? = TODO("not used")
    override suspend fun insert(scheme: ColorSchemeEntity): Long = TODO("not used")
    override suspend fun update(scheme: ColorSchemeEntity) = TODO("not used")
    override suspend fun delete(scheme: ColorSchemeEntity) = TODO("not used")
}

private class FakePortForwardDao : PortForwardDao {
    override suspend fun getForHost(hostId: Long): List<PortForwardEntity> = TODO("not used")
    override fun observeForHost(hostId: Long): Flow<List<PortForwardEntity>> = TODO("not used")
    override suspend fun insert(forward: PortForwardEntity): Long = TODO("not used")
    override suspend fun update(forward: PortForwardEntity) = TODO("not used")
    override suspend fun delete(forward: PortForwardEntity) = TODO("not used")
}

/**
 * A fake [SshConnection] whose [connect] suspends on [connectGate] instead of touching a real
 * socket - see SshConnection.kt's `open` markers, added for exactly this.
 */
private class FakeSshConnection(
    scope: CoroutineScope,
    context: Context,
    onDisconnected: (Boolean) -> Unit = {},
) : SshConnection(scope, context, onDisconnected) {
    val connectGate = CompletableDeferred<Unit>()

    @Volatile
    var connectError: Throwable? = null

    @Volatile
    var disconnectCalls = 0

    @Volatile
    var stopAllForwardsCalls = 0

    /**
     * Set the instant [connect] is actually reached and about to suspend on [connectGate] - unlike
     * SessionSummary.status, which is already CONNECTING the moment `openSession` creates the entry,
     * before the async `attemptConnect()` coroutine has even been scheduled. Tests poll this, not
     * `status`, to know the fake connection attempt is genuinely in flight.
     */
    @Volatile
    var connectStarted = false

    override suspend fun connect(
        host: String,
        port: Int,
        username: String,
        authMethod: AuthMethod,
        hostKeyVerifier: HostKeyVerifier,
        cols: Int,
        rows: Int,
        resilientSession: Boolean,
        cursorStyle: Int?,
        cursorBlink: Boolean,
        transcriptRows: Int?,
        hops: List<ProxyHop>,
    ) {
        connectStarted = true
        connectGate.await()
        connectError?.let { throw it }
    }

    override fun disconnect() {
        disconnectCalls++
    }

    override fun stopAllForwards() {
        stopAllForwardsCalls++
    }
}

class SessionManagerTest {

    private fun newManager(tempDir: File): SessionManager {
        val context = FakeContext(tempDir)
        return SessionManager(
            context,
            EmptyHostDao(),
            FakeTerminalProfileDao(),
            FakeColorSchemeDao(),
            FakePortForwardDao(),
            HostVerificationGate(),
            KeyboardInteractiveGate(),
            NoopSessionAlerts(context),
        )
    }

    private fun spec() = ConnectionSpec(
        hostname = "example.invalid",
        port = 22,
        username = "user",
        authMethod = AuthMethod.Password("hunter2"),
        hostId = null,
    )

    private fun summaryOf(manager: SessionManager, id: Long): SessionSummary =
        manager.summaries.value.first { it.id == id }

    private fun waitUntil(timeoutMs: Long = 5_000, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { message }
            Thread.sleep(1)
        }
    }

    private fun handleDisconnected(manager: SessionManager, id: Long, clean: Boolean) {
        SessionManager::class.java.getDeclaredMethod(
            "handleDisconnected",
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(manager, id, clean)
    }

    /**
     * `handleDisconnected` fires while `attemptConnect` is still suspended inside `connect()` - a
     * shell that ends (or a socket that drops) in the window between `connect()` starting its reader
     * coroutine and the CONNECTED promotion, per attemptConnect's own comment. The stale attempt must
     * not win the race and report a session that in fact just went RECONNECTING.
     */
    @Test
    fun generationGuard_staleAttemptDoesNotPromoteAfterConcurrentDisconnect() {
        val tempDir = createTempDir()
        val manager = newManager(tempDir)
        lateinit var fake: FakeSshConnection
        manager.connectionFactory = { id ->
            FakeSshConnection(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                FakeContext(tempDir),
            ).also { fake = it }
        }

        val id = manager.openSession(spec())
        manager.beginConnectIfNeeded(id, 80, 24, 8, 16)

        // Wait for attemptConnect() to actually be suspended inside connect().
        waitUntil(message = "timed out waiting for connect() to be reached") { fake.connectStarted }

        // Reach into the running SessionManager the same way SshConnection's real reader coroutine
        // would: fire the disconnect callback for this id while connect() is still suspended.
        handleDisconnected(manager, id, clean = false) // unclean drop -> RECONNECTING

        assertEquals(SessionStatus.RECONNECTING, summaryOf(manager, id).status)

        // Let the stale connect() attempt finish successfully, then check right away: it must not
        // overwrite the RECONNECTING status the fresher generation just set.
        fake.connectGate.complete(Unit)
        Thread.sleep(50) // give the stale attemptConnect() a chance to (wrongly) land its promotion
        assertNotEquals(
            "a stale attempt must never promote straight to CONNECTED",
            SessionStatus.CONNECTED,
            summaryOf(manager, id).status,
        )
    }

    /** `reconnectNow` must not touch a session that's mid-connect, already connected, or already retrying. */
    @Test
    fun reconnectNow_noOpsWhileConnectingReconnectingOrConnected() {
        val tempDir = createTempDir()
        val manager = newManager(tempDir)
        val factoryCalls = AtomicInteger(0)
        lateinit var fake: FakeSshConnection
        manager.connectionFactory = { id ->
            factoryCalls.incrementAndGet()
            FakeSshConnection(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                FakeContext(tempDir),
            ).also { fake = it }
        }

        val id = manager.openSession(spec())
        manager.beginConnectIfNeeded(id, 80, 24, 8, 16)
        waitUntil(message = "timed out waiting for connect() to be reached") { fake.connectStarted }

        var callsBefore = factoryCalls.get()
        manager.reconnectNow(id) // status is CONNECTING -> must no-op
        assertEquals(callsBefore, factoryCalls.get())

        fake.connectGate.complete(Unit)
        waitUntil(message = "timed out waiting for CONNECTED") {
            summaryOf(manager, id).status == SessionStatus.CONNECTED
        }

        manager.reconnectNow(id) // status is CONNECTED -> must no-op too
        assertEquals(callsBefore, factoryCalls.get())

        handleDisconnected(manager, id, clean = false) // unclean drop -> RECONNECTING
        assertEquals(SessionStatus.RECONNECTING, summaryOf(manager, id).status)
        callsBefore = factoryCalls.get()
        manager.reconnectNow(id) // status is RECONNECTING -> must no-op too
        assertEquals(callsBefore, factoryCalls.get())
    }

    /** `reconnectNow` on a DISCONNECTED or FAILED session replaces the connection and retries. */
    @Test
    fun reconnectNow_replacesConnectionWhenDisconnectedOrFailed() {
        val tempDir = createTempDir()
        val manager = newManager(tempDir)
        val factoryCalls = AtomicInteger(0)
        val connections = CopyOnWriteArrayList<FakeSshConnection>()
        manager.connectionFactory = { id ->
            factoryCalls.incrementAndGet()
            FakeSshConnection(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                FakeContext(tempDir),
            ).also { connections += it }
        }

        // DISCONNECTED case: a clean drop while still connecting.
        val disconnectedId = manager.openSession(spec())
        manager.beginConnectIfNeeded(disconnectedId, 80, 24, 8, 16)
        waitUntil(message = "timed out waiting for connect() to be reached") {
            connections.size >= 1 && connections[0].connectStarted
        }
        handleDisconnected(manager, disconnectedId, clean = true)
        assertEquals(SessionStatus.DISCONNECTED, summaryOf(manager, disconnectedId).status)

        var callsBefore = factoryCalls.get()
        manager.reconnectNow(disconnectedId)
        assertEquals(callsBefore + 1, factoryCalls.get())
        assertEquals(1, connections[0].disconnectCalls)
        // Once from handleDisconnected's own drop handling above, once from reconnectNow's own call
        // (see its doc) - reconnectNow doesn't know whether the entry it's replacing already had its
        // forwards released, so it always releases them again itself.
        assertEquals(2, connections[0].stopAllForwardsCalls)

        // FAILED case: connect() throws outright, no drop callback involved.
        val failedId = manager.openSession(spec())
        manager.beginConnectIfNeeded(failedId, 80, 24, 8, 16)
        waitUntil(message = "timed out waiting for connect() to be reached") {
            connections.size >= 3 && connections[2].connectStarted
        }
        connections[2].connectError = java.io.IOException("boom")
        connections[2].connectGate.complete(Unit)
        waitUntil(message = "timed out waiting for FAILED") {
            summaryOf(manager, failedId).status == SessionStatus.FAILED
        }

        callsBefore = factoryCalls.get()
        manager.reconnectNow(failedId)
        assertEquals(callsBefore + 1, factoryCalls.get())
    }

    /**
     * `closeSession` must complete a still-pending keyboard-interactive prompt for that session
     * rather than leaving it (and attemptConnect's `finally`) blocked forever - see closeSession's
     * own doc on this deadlock hazard.
     */
    @Test
    fun closeSession_completesAPendingKeyboardInteractivePrompt() {
        val tempDir = createTempDir()
        val gate = KeyboardInteractiveGate()
        val provider = gate.newProvider()
        val kiSpec = spec().copy(authMethod = AuthMethod.KeyboardInteractive(provider))

        val context = FakeContext(tempDir)
        val manager = SessionManager(
            context,
            EmptyHostDao(),
            FakeTerminalProfileDao(),
            FakeColorSchemeDao(),
            FakePortForwardDao(),
            HostVerificationGate(),
            gate,
            NoopSessionAlerts(context),
        )
        manager.connectionFactory = { id ->
            FakeSshConnection(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                FakeContext(tempDir),
            )
        }

        val id = manager.openSession(kiSpec)
        manager.beginConnectIfNeeded(id, 80, 24, 8, 16)
        gate.label(provider, "test")

        var promptAnswered = false
        val promptThread = Thread {
            promptAnswered = runBlocking {
                // getResponse() blocks on the same deferred closeSession() must complete.
                provider.getResponse("password:", false)
                true
            }
        }
        promptThread.start()

        waitUntil(message = "timed out waiting for the keyboard-interactive prompt to register") {
            gate.pending.value.isNotEmpty()
        }
        manager.closeSession(id)
        promptThread.join(5_000)

        assertTrue(
            "closeSession must unblock a coroutine waiting on a pending keyboard-interactive prompt",
            promptAnswered,
        )
        assertFalse(promptThread.isAlive)
    }

    /**
     * A corrupt/unrecognised `type` column on a saved forward must publish a FAILED tunnel status
     * with a message, not throw out of `startForwardInternal`.
     */
    @Test
    fun startForward_corruptTypeRow_publishesFailedInsteadOfCrashing() = runBlocking {
        val tempDir = createTempDir()
        val manager = newManager(tempDir)
        lateinit var fake: FakeSshConnection
        manager.connectionFactory = { id ->
            FakeSshConnection(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                FakeContext(tempDir),
            ).also { fake = it }
        }

        val id = manager.openSession(spec())
        manager.beginConnectIfNeeded(id, 80, 24, 8, 16)
        waitUntil(message = "timed out waiting for connect() to be reached") { fake.connectStarted }
        fake.connectGate.complete(Unit)
        waitUntil(message = "timed out waiting for CONNECTED") {
            summaryOf(manager, id).status == SessionStatus.CONNECTED
        }

        val corrupt = PortForwardEntity(
            id = 1,
            hostId = 1,
            type = "NOT_A_REAL_TYPE",
            bindAddress = null,
            bindPort = 8080,
            targetHost = null,
            targetPort = null,
            autoStart = false,
        )

        manager.startForward(id, corrupt) // must not throw

        val tunnel = summaryOf(manager, id).tunnels.first { it.forwardId == 1L }
        assertEquals(TunnelState.FAILED, tunnel.state)
        assertTrue(tunnel.error?.contains("Unrecognised forward type") == true)
    }

    /**
     * A FAILED attempt inside `reconnectWithBackoff` (`attemptConnect()` throwing rather than dying
     * via `onDisconnected`) must flip the entry back to RECONNECTING and keep retrying on a doubled,
     * capped delay - not stop silently, per reconnectWithBackoff's own comment. Real delays, not a
     * virtual clock: no kotlinx-coroutines-test in this project.
     */
    @Test
    fun backoffResume_failedAttemptFlipsBackToReconnectingAndRetries() {
        val tempDir = createTempDir()
        val manager = newManager(tempDir)
        val callCount = AtomicInteger(0)
        val callTimestamps = CopyOnWriteArrayList<Long>()
        manager.connectionFactory = { id ->
            val call = callCount.incrementAndGet()
            callTimestamps += System.nanoTime()
            FakeSshConnection(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                FakeContext(tempDir),
            ).apply {
                // Call 1: the initial connect, succeeds. Call 2: the backoff loop's first retry,
                // fails outright. Call 3: its second retry, succeeds.
                if (call == 2) connectError = java.io.IOException("boom")
                connectGate.complete(Unit)
            }
        }

        val id = manager.openSession(spec())
        manager.beginConnectIfNeeded(id, 80, 24, 8, 16)
        waitUntil(message = "timed out waiting for CONNECTED") {
            summaryOf(manager, id).status == SessionStatus.CONNECTED
        }
        assertEquals(1, callCount.get())

        handleDisconnected(manager, id, clean = false) // unclean -> RECONNECTING, starts the backoff loop
        assertEquals(SessionStatus.RECONNECTING, summaryOf(manager, id).status)

        // Backoff loop's first retry (call 2, ~2s in) fails and must flip back to RECONNECTING
        // rather than getting stuck FAILED.
        waitUntil(timeoutMs = 6_000, message = "timed out waiting for retry #1") { callCount.get() >= 2 }

        // Second retry (call 3, doubled delay: ~4s later) succeeds - only possible if the FAILED
        // retry above did flip back to RECONNECTING and the loop kept going; callCount reaching 3
        // is the real pin, not a status read in between that could pass before the flip-back lands.
        waitUntil(timeoutMs = 8_000, message = "timed out waiting for retry #2 to connect") {
            summaryOf(manager, id).status == SessionStatus.CONNECTED
        }
        assertEquals(3, callCount.get())

        // Pin the doubling itself, not just that a retry eventually happened: the gap between retry
        // #1 and retry #2 must be roughly twice the gap between the initial connect and retry #1 (2s
        // vs 4s), with slack for scheduler jitter - not merely "some retry happened eventually".
        val firstGapMs = (callTimestamps[1] - callTimestamps[0]) / 1_000_000
        val secondGapMs = (callTimestamps[2] - callTimestamps[1]) / 1_000_000
        assertTrue(
            "expected the second backoff gap (${secondGapMs}ms) to be roughly double the first (${firstGapMs}ms)",
            secondGapMs >= firstGapMs * 1.5,
        )
    }
}

private fun createTempDir(): File =
    kotlin.io.path.createTempDirectory("session-manager-test").toFile().apply { deleteOnExit() }
