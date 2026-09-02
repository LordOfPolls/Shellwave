package io.github.lordofpolls.shellwave.ssh

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardType
import io.github.lordofpolls.shellwave.core.prefs.AppearancePreferences
import io.github.lordofpolls.shellwave.core.prefs.SupportPreferences
import io.github.lordofpolls.shellwave.service.SessionAlerts
import io.github.lordofpolls.shellwave.service.SessionService
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.terminal.TerminalCursorStyle
import io.github.lordofpolls.shellwave.terminal.applyColorSchemeAsDefault
import io.github.lordofpolls.shellwave.terminal.applyColorSchemeLive
import io.github.lordofpolls.shellwave.terminal.toEngineConstant
import io.github.lordofpolls.shellwave.ui.design.SchemeHarmonizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Shared with `ScriptRunner`'s capture mode so a script run and an interactive session trust the same known_hosts file. */
internal const val KNOWN_HOSTS_FILE_NAME = "known_hosts"
private const val RECONNECT_INITIAL_DELAY_MS = 2_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L

/**
 * Fallback for [SessionManager.harmonized] before SessionManager.setDynamicAccent has ever been
 * called - a session opened in the same frame as app startup, before `MainActivity` reads the real
 * dynamic accent. Reuses ShellwaveTheme's own dynamic-colour-off fallback rather than an arbitrary
 * colour, so an early harmonize pass still nudges hue toward this app's accent instead of a
 * meaningless placeholder.
 */
private const val FALLBACK_ACCENT_ARGB = 0xFF4D7C0F.toInt()

/**
 * A forward bound to `0.0.0.0` is reachable from every device on the user's network, well beyond
 * this phone, so loopback-only is the default and a blank [PortForwardEntity.bindAddress] falls
 * back to it. Binding to all interfaces only ever happens when a user types `0.0.0.0` themselves in
 * TunnelsSection, which spells out the consequence next to the field.
 */
internal const val LOOPBACK_ADDRESS = "127.0.0.1"

/** `BindException` means "port already in use" - the common, expected failure. */
private fun describeForwardFailure(e: Throwable): String =
    if (e is java.net.BindException) "Port already in use" else e.message
        ?: "Failed to start forward"

enum class SessionStatus { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }

/**
 * `RUNNING`/`FAILED` mirror [SshConnection.startLocalForward]/[SshConnection.startRemoteForward]'s
 * `Result`; `STOPPED` is either a deliberate SessionManager.stopForward or
 * [SshConnection.onForwardStopped] firing with no error. A forward with no entry in
 * [SessionSummary.tunnels] has never been started this session: neither stopped nor failed.
 */
enum class TunnelState { RUNNING, FAILED, STOPPED }

/** See `TunnelState`'s doc; [error] is non-null only for [TunnelState.FAILED]. */
data class TunnelStatus(val forwardId: Long, val state: TunnelState, val error: String? = null)

/** Measured terminal geometry, kept alongside a session so a reconnect can allocate the same size PTY without waiting for a remeasure. */
data class TerminalSize(val cols: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int)

/**
 * One row of what SessionManager.summaries publishes to the UI. [connection] is always the
 * current [SshConnection] for this id - stable while connected, replaced wholesale on a
 * reconnect (a new TCP connection means a new PTY and a new [com.termux.terminal.TerminalEmulator]
 * - there is no way to hand the old shell's buffer to a new one, so a reconnect starts a fresh
 * screen, same as any other terminal app after a dropped connection).
 */
data class SessionSummary(
    val id: Long,
    val label: String,
    val status: SessionStatus,
    val error: String?,
    val connection: SshConnection,
    /**
     * The origin SessionCard's uptime counts from, or `null` when the session isn't connected.
     *
     * On the session rather than in the card's own `remember`: a card is composed and disposed every
     * time the user visits Sessions while the connection underneath keeps running, so card-local
     * state restarted the clock on each visit and an hour-old session read `3s` on sight.
     *
     * `elapsedRealtime`, since this is a duration - a wall clock an NTP correction can move would make
     * uptime jump or go negative. It counts through sleep, which `uptimeMillis` does not.
     */
    val connectedAtElapsedRealtime: Long?,
    /** Kept so a caller can tell which sessions share a host. Overrides are already resolved here. */
    val hostId: Long?,
    /**
     * Host override, else the app-wide default. Resolved at connect and kept live-updated by
     * [SessionManager.applyDefaultColorScheme] for sessions with no override, so the
     * selection-highlight tint always matches [connection]'s actual emulator colours.
     */
    val colorScheme: ColorSchemeEntity,
    /** `null` to use whatever app-wide default the sessions screen was passed. */
    val terminalProfileId: Long?,
    /** `null` for DEFAULT_KEY_BAR_KEYS. */
    val keyBarLayoutId: Long?,
    /**
     * Keyed by [PortForwardEntity.id]. Read by TunnelsSection to show live status next to a host's
     * saved forwards, and by SessionService to fold a running-tunnel count into the foreground
     * notification. Empty when nothing has been started this session.
     */
    val tunnels: List<TunnelStatus> = emptyList(),
)

/**
 * Owns every open SshConnection, so rotation, fold/unfold and backgrounding never drop one.
 * SessionService keeps the process alive; this keeps the data alive, and the Activity just observes
 * [summaries].
 *
 * A new session can't call [SshConnection.connect] until the UI has measured a terminal size, cols
 * and rows being needed to allocate the PTY. [openSession] registers it and returns an id;
 * [beginConnectIfNeeded] is called once a measurement exists, and is idempotent.
 */
@Singleton
class SessionManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val hostDao: HostDao,
    private val terminalProfileDao: TerminalProfileDao,
    private val colorSchemeDao: ColorSchemeDao,
    private val portForwardDao: PortForwardDao,
    private val hostVerificationGate: HostVerificationGate,
    private val keyboardInteractiveGate: KeyboardInteractiveGate,
    private val sessionAlerts: SessionAlerts,
) {
    // Not tied to any Activity/Composition lifecycle; a coroutine launched here must keep running
    // (a reconnect backoff, a reader loop) regardless of what the UI is doing.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val nextId = AtomicLong(1)
    private val entries = ConcurrentHashMap<Long, Entry>()

    /** Hilt's generated factory cannot omit a defaulted constructor arg, so the test seam is a settable var. */
    @VisibleForTesting
    internal var connectionFactory: (Long) -> SshConnection = ::newConnection

    private val _summaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    val summaries: StateFlow<List<SessionSummary>> = _summaries

    // The live Material You accent, as plain packed ARGB instead of a Compose Color: this class has
    // no Compose dependency anywhere else, and pulling in `dynamicLightColorScheme` to read one
    // colour would be the first Compose import in this package. MainActivity already resolves the
    // dynamic scheme for `ShellwaveTheme`, so it hands over the one number needed here.
    @Volatile
    private var dynamicAccentArgb: Int = FALLBACK_ACCENT_ARGB

    // Every open session's terminal fills the same screen area, so once any one of them has been
    // measured, a brand-new session can reuse that size immediately - see openSession's doc for why
    // this matters beyond a nice-to-have.
    @Volatile
    private var lastKnownSize: TerminalSize? = null

    private class Entry(
        val id: Long,
        val spec: ConnectionSpec,
        val label: String,
        @Volatile var connection: SshConnection,
        @Volatile var status: SessionStatus,
        @Volatile var error: String? = null,
        // Published as SessionSummary.connectedAtElapsedRealtime: see that field's doc for why the
        // connect instant is session state instead of the uptime card's own remember. Set only
        // where `status` is promoted to CONNECTED, and cleared everywhere `status` leaves it, so
        // "non-null" and "connected" can never disagree.
        @Volatile var connectedAtElapsedRealtime: Long? = null,
        @Volatile var lastSize: TerminalSize? = null,
        @Volatile var started: Boolean = false,
        @Volatile var backoffJob: Job? = null,
        // Set fresh by attemptConnect() on every (re)connect. effectiveColorScheme is what
        // SessionSummary publishes and what this entry's emulator colours were last set to;
        // followsDefaultColorScheme is false exactly when this host has its own override, which is
        // what tells applyDefaultColorScheme() to leave this entry alone on a Settings edit.
        @Volatile var effectiveColorScheme: ColorSchemeEntity = DEFAULT_COLOR_SCHEME,
        @Volatile var followsDefaultColorScheme: Boolean = true,
        // Refreshed by attemptConnect() on every (re)connect, same as the colour fields above, and
        // published as ids and not resolved entities - see SessionSummary.
        @Volatile var terminalProfileId: Long? = null,
        @Volatile var keyBarLayoutId: Long? = null,
        // Keyed by PortForwardEntity.id. Reset to empty at the top of every attemptConnect() and
        // cleared again by handleDisconnected(), the same "fresh per attempt" convention as the
        // colour and profile fields above.
        @Volatile var tunnels: Map<Long, TunnelStatus> = emptyMap(),
        // Bumped at the start of every attemptConnect() (a fresh connect or a reconnect) and again
        // by handleDisconnected(): see attemptConnect's comment on why this exists.
        val generation: AtomicLong = AtomicLong(0),
    )

    /**
     * Connects immediately if any other session has already measured a terminal size.
     *
     * Sessions #2 and #3 must not wait to be selected first: SessionsScreen only ever composes a
     * terminal for the selected session id, so one that is never selected would never get an
     * `onMeasured` callback and would sit in CONNECTING forever. Only the process's very first session
     * has no size to reuse, and with one session that session is by definition the one on screen.
     */
    fun openSession(spec: ConnectionSpec): Long {
        val id = nextId.getAndIncrement()
        val connection = connectionFactory(id)
        // Always includes the port, non-default or otherwise: two sessions to the same host on
        // different ports are otherwise distinguishable only by status glyph, which the list pane
        // makes worse by showing several sessions side by side.
        entries[id] = Entry(
            id,
            spec,
            "${spec.username}@${spec.hostname}:${spec.port}",
            connection,
            SessionStatus.CONNECTING
        )
        publish()
        // The entry above is visible before this starts the service, so if SessionService's
        // onCreate races ahead of openSession's caller, it still observes a non-empty list.
        ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        lastKnownSize?.let { size ->
            beginConnectIfNeeded(
                id,
                size.cols,
                size.rows,
                size.cellWidthPx,
                size.cellHeightPx
            )
        }
        SupportPreferences.recordUse(context)
        return id
    }

    /** No-op after the first call for a given [id] (across recomposition, rotation, a reused cached size, ...) - see class doc. */
    fun beginConnectIfNeeded(id: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val entry = entries[id] ?: return
        synchronized(entry) {
            if (entry.started) return
            entry.started = true
        }
        val size = TerminalSize(cols, rows, cellWidthPx, cellHeightPx)
        entry.lastSize = size
        lastKnownSize = size
        managerScope.launch { attemptConnect(entry) }
    }

    /** Records the latest measured size so a future reconnect allocates a PTY at the right geometry, and so the next new session (see [openSession]) can reuse it. */
    fun updateSize(id: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val size = TerminalSize(cols, rows, cellWidthPx, cellHeightPx)
        entries[id]?.lastSize = size
        lastKnownSize = size
    }

    /**
     * Manual retry for a session sitting in DISCONNECTED or FAILED.
     *
     * [SshConnection.stopAllForwards] runs synchronously here, before `disconnect()`, matching the
     * ordering [handleDisconnected] uses. Otherwise the old forwards are released only by
     * `disconnect()`'s own call, which it launches onto [Dispatchers.IO] without waiting - so the fresh
     * connection on the next line could race the old listeners' `ServerSocket.close()` and fail with an
     * intermittent "Port already in use" on a reconnect that otherwise worked.
     */
    fun reconnectNow(id: Long) {
        val entry = entries[id] ?: return
        if (entry.status == SessionStatus.CONNECTING || entry.status == SessionStatus.RECONNECTING || entry.status == SessionStatus.CONNECTED) return
        entry.backoffJob?.cancel()
        entry.connection.stopAllForwards() // release bound ports before disconnect()'s async teardown gets to it - see this method's doc
        entry.connection.disconnect() // the old, dead connection - freshen up before retrying it
        entry.connection = connectionFactory(entry.id)
        managerScope.launch { attemptConnect(entry) }
    }

    /**
     * Tears down and forgets one session. Also a path that can unblock a keyboard-interactive prompt
     * still on screen for this session: attemptConnect()'s own finally can't run while that prompt is
     * pending, because the coroutine sitting in connect() is the very one that's blocked waiting on it:
     * see KeyboardInteractiveGate.cancel's doc. Calling cancel() here, from outside that blocked
     * coroutine, is what actually completes the deferred and frees it. KeyboardInteractiveDialog's own
     * Cancel button reaches the same gate via `prompt.respond("")`, so this is not the only way out:
     * just the one that also tears down the session rather than merely dismissing the prompt.
     */
    fun closeSession(id: Long) {
        val entry = entries.remove(id) ?: return
        sessionAlerts.forget(id)
        entry.backoffJob?.cancel()
        entry.connection.disconnect()
        (entry.spec.authMethod as? AuthMethod.KeyboardInteractive)?.let {
            keyboardInteractiveGate.cancel(
                it.provider
            )
        }
        publish()
    }

    /** The persistent notification's "disconnect all" action, and used when the user explicitly wants a clean slate. */
    fun disconnectAll() {
        entries.keys.toList().forEach { closeSession(it) }
    }

    /**
     * Called from MainActivity, which already computes `dynamicLightColorScheme(context).primary` for
     * ShellwaveTheme.
     *
     * [argb] only takes effect on the next [harmonized] call - a fresh connect, or the next app-wide
     * default scheme edit. A session already open on an older accent is not retroactively
     * re-harmonized: nothing observes accent changes as a Flow the way [ColorSchemeDao.observeDefault]
     * observes scheme edits, so a wallpaper change alone does not ripple into an open session.
     */
    fun setDynamicAccent(argb: Int) {
        dynamicAccentArgb = argb
    }

    /**
     * The "Exact scheme colours" escape hatch is read fresh from [AppearancePreferences] on every call
     * and not cached, matching every other per-attempt-fresh read in [attemptConnect]: a user who flips
     * the setting while a session is disconnected should have its next connect honour the new value.
     *
     * This never mutates [scheme] or anything in the database - `ColorSchemeDao` only ever stores the
     * user-authored original, and harmonization is purely a rendering-time transform.
     */
    private fun harmonized(scheme: ColorSchemeEntity): ColorSchemeEntity =
        SchemeHarmonizer.resolve(
            scheme,
            dynamicAccentArgb,
            AppearancePreferences.getExactSchemeColours(context)
        )

    /**
     * The app-wide default colour scheme changed (a Settings edit, collected live by MainActivity from
     * [ColorSchemeDao.observeDefault]). Updates the engine's static default template, so a brand-new
     * session with no host override picks it up at construction, plus every open session still
     * following the default. not every open session: a host with its own `colorSchemeId` override must
     * never be stomped by an edit to the default.
     *
     * [scheme] is [harmonized] once, here, before either downstream write, so the static template and
     * every live entry render the same colours instead of a mix.
     */
    fun applyDefaultColorScheme(scheme: ColorSchemeEntity) {
        val resolved = harmonized(scheme)
        applyColorSchemeAsDefault(resolved)
        entries.values.filter { it.followsDefaultColorScheme }
            .forEach { applySchemeToEntry(it, resolved) }
        publish()
    }

    /**
     * Applies [scheme] to one session's own live colours (SshConnection.emulatorOrNull's `mColors`) and
     * nudges its [SshConnection.outputTick] so its next frame actually redraws - mutating a
     * `TerminalColors` instance's array in place is otherwise invisible to Compose. Records [scheme] on
     * [entry] too, so `SessionSummary.colorScheme` always agrees with what was actually applied. This
     * and [applyDefaultColorScheme]'s call to `applyColorSchemeAsDefault` are the only two places
     * anything in this app writes engine colour objects.
     */
    private fun applySchemeToEntry(entry: Entry, scheme: ColorSchemeEntity) {
        entry.effectiveColorScheme = scheme
        entry.connection.emulatorOrNull?.let { emulator ->
            applyColorSchemeLive(scheme, emulator.mColors)
            entry.connection.notifyColorsChanged()
        }
    }

    private fun newConnection(id: Long): SshConnection =
        SshConnection(
            managerScope,
            context,
            onDisconnected = { clean -> handleDisconnected(id, clean) },
            onForwardStopped = { forwardId, error -> handleForwardStopped(id, forwardId, error) },
        )

    /**
     * Manual start of one saved forward against an already-[SessionStatus.CONNECTED] session -
     * TunnelsSection's per-forward "Start" action. A no-op (not a queued/pending start) for any other
     * status: there is no sensible "start this once it happens to connect" semantics here - auto-start
     * (see [attemptConnect]) already covers "start on connect" for forwards with
     * [PortForwardEntity.autoStart] set, and a forward without that flag manually started here is meant
     * to be a one-off and no promise to retry.
     */
    suspend fun startForward(sessionId: Long, forward: PortForwardEntity) {
        val entry = entries[sessionId] ?: return
        if (entry.status != SessionStatus.CONNECTED) return
        startForwardInternal(entry, forward)
        publish()
    }

    /** SessionsScreen/TunnelsSection's per-forward "Stop" action. */
    suspend fun stopForward(sessionId: Long, forwardId: Long) {
        val entry = entries[sessionId] ?: return
        entry.connection.stopForward(forwardId)
        entry.tunnels = entry.tunnels + (forwardId to TunnelStatus(forwardId, TunnelState.STOPPED))
        publish()
    }

    /**
     * Shared by [startForward]'s manual path and [attemptConnect]'s auto-start path so the two can't
     * drift. [PortForwardType.DYNAMIC] needs no `targetHost`/`targetPort` - a SOCKS5 listener has no
     * fixed target - while `LOCAL`/`REMOTE` fail with a visible message if either is missing, as does
     * an unrecognised `type` string. Both can only come from a stale or corrupt row.
     */
    private suspend fun startForwardInternal(entry: Entry, forward: PortForwardEntity) {
        val bindAddress = forward.bindAddress?.takeIf { it.isNotBlank() } ?: LOOPBACK_ADDRESS
        val targetHost = forward.targetHost
        val targetPort = forward.targetPort
        val result =
            when (runCatching { PortForwardType.valueOf(forward.type) }.getOrNull()) {
                PortForwardType.LOCAL ->
                    if (targetHost == null || targetPort == null) {
                        Result.failure(IllegalStateException("Local forward is missing a target host/port"))
                    } else {
                        entry.connection.startLocalForward(
                            forward.id,
                            bindAddress,
                            forward.bindPort,
                            targetHost,
                            targetPort
                        )
                    }

                PortForwardType.REMOTE ->
                    if (targetHost == null || targetPort == null) {
                        Result.failure(IllegalStateException("Remote forward is missing a target host/port"))
                    } else {
                        entry.connection.startRemoteForward(
                            forward.id,
                            bindAddress,
                            forward.bindPort,
                            targetHost,
                            targetPort
                        )
                    }

                PortForwardType.DYNAMIC -> entry.connection.startDynamicForward(
                    forward.id,
                    bindAddress,
                    forward.bindPort
                )

                null -> Result.failure(IllegalStateException("Unrecognised forward type: ${forward.type}"))
            }
        entry.tunnels =
            entry.tunnels +
                    (forward.id to
                            result.fold(
                                onSuccess = { TunnelStatus(forward.id, TunnelState.RUNNING) },
                                onFailure = {
                                    TunnelStatus(
                                        forward.id,
                                        TunnelState.FAILED,
                                        describeForwardFailure(it)
                                    )
                                },
                            ))
    }

    private fun handleForwardStopped(id: Long, forwardId: Long, error: String?) {
        val entry = entries[id] ?: return
        val state = if (error != null) TunnelState.FAILED else TunnelState.STOPPED
        entry.tunnels = entry.tunnels + (forwardId to TunnelStatus(forwardId, state, error))
        publish()
    }

    // connect() starts the reader coroutine before it returns, so a shell that ends or a socket that
    // drops in that window can call handleDisconnected() concurrently, before this sets CONNECTED.
    // Unguarded, that write lands after the DISCONNECTED one and overwrites it, leaving a dead
    // session showing connected forever with no reader and no backoff.
    //
    // Reasoned from the code, not reproduced: hitting the window needs sub-millisecond timing that
    // typing `exit` into a connected shell doesn't exercise. myGeneration is snapshotted before
    // connect(); handleDisconnected() bumps it, so the promotion only lands if nothing intervened.
    private suspend fun attemptConnect(entry: Entry) {
        val size = entry.lastSize ?: return
        val myGeneration = entry.generation.incrementAndGet()
        entry.status = SessionStatus.CONNECTING
        entry.error = null
        // A reconnect is a new connection, so its uptime starts over instead of counting through
        // the gap: see SessionSummary.connectedAtElapsedRealtime.
        entry.connectedAtElapsedRealtime = null
        // Fresh per attempt, same as the colour/profile fields below - a forward left RUNNING/
        // FAILED from a previous attempt would otherwise look live across a reconnect that hasn't
        // auto-started anything yet. handleDisconnected() also clears this (see its own doc) for
        // the gap between a drop and the next attemptConnect().
        entry.tunnels = emptyMap()
        publish()
        val knownHosts = TofuKnownHostsVerifier(
            File(context.filesDir, KNOWN_HOSTS_FILE_NAME),
            hostVerificationGate,
            entry.id,
            entry.label
        )
        // Re-labelled on every attempt (including reconnects), since cancel() below clears it - see
        // KeyboardInteractiveGate.label's doc.
        (entry.spec.authMethod as? AuthMethod.KeyboardInteractive)?.let {
            keyboardInteractiveGate.label(
                it.provider,
                entry.label
            )
        }
        // Per-host overrides, read fresh on every attempt rather than cached: an override edited
        // while this session was disconnected should be honoured by its next connection. Falls back
        // host override -> app-wide default -> code default, and a deleted override row falls back
        // the same way a never-saved default does.
        val host = entry.spec.hostId?.let { hostDao.getById(it) }
        val profile = host?.terminalProfileId?.let { terminalProfileDao.getById(it) }
            ?: terminalProfileDao.getDefault() ?: DEFAULT_TERMINAL_PROFILE
        // harmonized() wraps whichever scheme this chain resolves to, so a fresh connect renders
        // the harmonized palette just as a live default-scheme edit does.
        val scheme = harmonized(host?.colorSchemeId?.let { colorSchemeDao.getById(it) }
            ?: colorSchemeDao.getDefault() ?: DEFAULT_COLOR_SCHEME)
        // Whether a later applyDefaultColorScheme() (a Settings edit to the app-wide default)
        // should touch this session: see that function's doc and Entry.followsDefaultColorScheme.
        entry.followsDefaultColorScheme = host?.colorSchemeId == null
        entry.terminalProfileId = host?.terminalProfileId
        entry.keyBarLayoutId = host?.keyBarLayoutId
        // Sets the engine's static construction-time template to this session's resolved scheme :
        // applySchemeToEntry below (right after connect() constructs the emulator) overrides this
        // session's own live colours regardless, so a race with a concurrent connect() on a
        // different host briefly writing a different template here can't leave this session showing
        // the wrong colours; it only affects how correct the very first rendered frame looks before
        // that live override lands.
        applyColorSchemeAsDefault(scheme)
        try {
            entry.connection.connect(
                entry.spec.hostname,
                entry.spec.port,
                entry.spec.username,
                entry.spec.authMethod,
                knownHosts,
                size.cols,
                size.rows,
                entry.spec.resilientSession,
                cursorStyle = TerminalCursorStyle.fromStored(profile.cursorStyle)
                    .toEngineConstant(),
                cursorBlink = profile.cursorBlink,
                transcriptRows = profile.scrollbackLines,
                hops = entry.spec.proxyHops,
            )
            if (entry.generation.get() == myGeneration) {
                entry.status = SessionStatus.CONNECTED
                entry.error = null
                // Same generation guard as the status write it accompanies: a stale attempt that
                // loses the CONNECTED race must not leave a connect instant behind either, or a
                // dead session would show a running clock.
                entry.connectedAtElapsedRealtime = SystemClock.elapsedRealtime()
                // No-ops unless this session was reported dropped, so a first connect stays quiet.
                sessionAlerts.reconnected(entry.id, entry.label)
            }
            // Auto-start runs on every successful (re)connect, including a reconnect after a drop.
            // Guarded like the CONNECTED promotion above: a generation bump means a newer attempt
            // owns this entry, and starting forwards against a connection nobody considers current
            // would leak bound ports closeSession()'s disconnect() has no reason to look for.
            if (entry.generation.get() == myGeneration && entry.spec.hostId != null) {
                portForwardDao.getForHost(entry.spec.hostId).filter { it.autoStart }
                    .forEach { startForwardInternal(entry, it) }
            }
            // The static template above already primed a close-enough first frame; this is what
            // actually guarantees this session's own emulator ends up with its resolved scheme,
            // regardless of what any concurrently-connecting sibling session did to the shared
            // template in between - see applySchemeToEntry's doc.
            applySchemeToEntry(entry, scheme)
            if (entry.spec.hostId != null) hostDao.touchLastConnected(
                entry.spec.hostId,
                System.currentTimeMillis()
            )
        } catch (e: Exception) {
            if (entry.generation.get() == myGeneration) {
                entry.status = SessionStatus.FAILED
                entry.error = describeConnectFailure(e, entry.spec.authMethod)
            }
        } finally {
            // The attempt is over one way or another - see HostVerificationGate's class doc.
            knownHosts.cancel()
            // Same reasoning, for the keyboard-interactive prompt dialog: see
            // KeyboardInteractiveGate's class doc.
            (entry.spec.authMethod as? AuthMethod.KeyboardInteractive)?.let {
                keyboardInteractiveGate.cancel(
                    it.provider
                )
            }
        }
        publish()
    }

    private fun handleDisconnected(id: Long, clean: Boolean) {
        val entry = entries[id] ?: return
        entry.generation.incrementAndGet() // invalidate any attemptConnect() still unwinding for this connection - see its comment
        // Release every bound local port the moment a drop is detected, rather than waiting for
        // reconnectWithBackoff()'s own disconnect() before its first retry - otherwise the port
        // stays occupied and the next start fails. A local forward's ServerSocket is independent of
        // the now-dead ssh transport, so nothing else would close it in the meantime.
        entry.connection.stopAllForwards()
        entry.tunnels = emptyMap()
        // Not connected any more, so there is no uptime to show: see SessionSummary
        // .connectedAtElapsedRealtime. attemptConnect() clears this too, but a drop that never gets
        // as far as a retry (a clean exit) has no attemptConnect() coming.
        entry.connectedAtElapsedRealtime = null
        if (clean) {
            entry.status = SessionStatus.DISCONNECTED
            entry.error = null
            sessionAlerts.forget(id)
            publish()
        } else {
            entry.status = SessionStatus.RECONNECTING
            entry.error = "Connection lost - reconnecting"
            sessionAlerts.dropped(id, entry.label)
            publish()
            // Cancel any backoff loop already in flight before starting a new one. Without this, a
            // connection that drops again mid-reconnect fires this path a second time while the
            // first loop is still retrying, and nothing else deduplicates the two.
            entry.backoffJob?.cancel()
            entry.backoffJob = managerScope.launch { reconnectWithBackoff(entry) }
        }
    }

    /** Exponential backoff, capped, for as long as [entries] still holds this id and nothing else has intervened. */
    private suspend fun reconnectWithBackoff(entry: Entry) {
        var delayMs = RECONNECT_INITIAL_DELAY_MS
        while (true) {
            delay(delayMs)
            if (entries[entry.id] !== entry) return // closed, or replaced by a manual reconnect
            if (entry.status != SessionStatus.RECONNECTING) return
            entry.connection.disconnect() // the old, dead connection - freshen up before retrying it
            entry.connection = connectionFactory(entry.id)
            attemptConnect(entry)
            if (entry.status == SessionStatus.CONNECTED) return
            if (entries[entry.id] !== entry) return
            if (entry.status != SessionStatus.RECONNECTING) {
                // attemptConnect() failed outright (SessionStatus.FAILED) instead of dying via
                // onDisconnected: keep retrying on the same backoff instead of stopping silently.
                entry.status = SessionStatus.RECONNECTING
                publish()
            }
            delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }
    }

    private fun publish() {
        _summaries.value =
            entries.values.sortedBy { it.id }
                .map {
                    SessionSummary(
                        it.id,
                        it.label,
                        it.status,
                        it.error,
                        it.connection,
                        it.connectedAtElapsedRealtime,
                        it.spec.hostId,
                        it.effectiveColorScheme,
                        it.terminalProfileId,
                        it.keyBarLayoutId,
                        it.tunnels.values.toList(),
                    )
                }
    }
}
