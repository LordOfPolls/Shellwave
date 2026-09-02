package io.github.lordofpolls.shellwave.ssh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import io.github.lordofpolls.shellwave.R
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.StreamCopier
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.xfer.FileTransfer
import net.schmizz.sshj.xfer.TransferListener
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "SshConnection"
private const val READ_BUFFER_SIZE = 4096

/** See `SshConnection.onBell`. */
private const val BELL_CHANNEL_ID = "bell"
private const val BELL_NOTIFICATION_ID = 2
private const val BELL_VIBRATION_MS = 150L

/**
 * The line sent as a resilient session's first input.
 *
 * It tests `command -v tmux` first so a missing binary explains itself and leaves the login shell
 * running, instead of showing an empty terminal or a "tmux: not found" that reads as a broken
 * connection. `new -A` is the only invocation correct both times: plain `new` fails once the
 * session exists, which is the reattach-after-reconnect case, and plain `attach` fails on the first
 * connection. `exec` replaces the login shell instead of leaving it as a parent, so exiting tmux
 * does not strand an orphan behind it.
 *
 * The session name is fixed, not per-host, so it is one well-known name the user can attach to by
 * hand from any other terminal.
 */
internal const val RESILIENT_SESSION_BOOTSTRAP =
    "command -v tmux >/dev/null 2>&1 && exec tmux new -A -s shellwave || " +
            "echo '[shellwave] tmux not found on this host - staying in a plain shell.'"

/**
 * One SSH connection driving one [TerminalEmulator]. Owns the sshj session and shell plus the
 * reader coroutine feeding shell output into the emulator; several coexist under [SessionManager].
 * Host key verification is mandatory: see [connect].
 *
 * TerminalEmulator and [com.termux.terminal.TerminalBuffer] have no internal synchronisation, since
 * upstream assumes a single-threaded caller, so every call into `emulator` - `render` in the draw
 * phase included - is confined to [Dispatchers.Main], and socket I/O stays on [Dispatchers.IO] or
 * throws `NetworkOnMainThreadException`.
 *
 * [onDisconnected] fires once from the reader coroutine when the shell's input stream ends:
 * `clean = true` for a normal EOF, `clean = false` for an `IOException`, covering network loss and
 * sshj's keepalive giving up. It is suppressed when [disconnect] came first.
 */
open class SshConnection(
    private val scope: CoroutineScope,
    private val context: Context,
    private val onDisconnected: (clean: Boolean) -> Unit = {},
    /**
     * Fires when a running local or dynamic forward's accept loop ends on its own - `error == null` for
     * a deliberate [stopForward]/[stopAllForwards] call (sshj's [LocalPortForwarder.listen] /
     * Socks5Forwarder.listen both return normally once their [ServerSocket] is closed: see
     * [startLocalForward]/[startDynamicForward]'s docs), or non-null for a genuine runtime failure. Not
     * fired for [startLocalForward]/[startRemoteForward]/[startDynamicForward] themselves failing to
     * start in the first place: that is reported synchronously via their `Result`, which is all
     * `SessionManager.startForwardInternal` needs for that case.
     */
    private val onForwardStopped: (forwardId: Long, error: String?) -> Unit = { _, _ -> },
) : TerminalOutput() {

    private val ssh = SSHClient()
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var readerJob: Job? = null
    private val closing = AtomicBoolean(false)

    // Every intermediate SSHClient/DirectConnection connect() built to reach `ssh` through, empty
    // for the common no-jump case. Torn down by disconnect() after `ssh` itself, since those
    // intermediates are still carrying its traffic until that point.
    private var chainResources: ProxyChainResources = ProxyChainResources(emptyList(), emptyList())

    // Keyed by PortForwardEntity.id rather than bind port, since SessionManager always addresses a
    // forward by its stored row id.
    private val localForwarders = ConcurrentHashMap<Long, LocalPortForwarder>()
    private val remoteForwards = ConcurrentHashMap<Long, RemotePortForwarder.Forward>()

    // A separate map from localForwarders, since a SOCKS5 listener has no sshj LocalPortForwarder
    // of its own to hold. Not merged into one `Any`-typed map, so stopForward/stopAllForwards stay
    // straight-line code.
    private val dynamicForwarders = ConcurrentHashMap<Long, Socks5Forwarder>()

    // The SFTP client, or nothing on the SCP fallback path - SCPFileTransfer opens and closes its
    // own Session per call and exposes no handle to interrupt early, so cancelActiveTransfer is
    // best-effort there. Closing this from another thread aborts a blocked SFTP read/write, which
    // makes a transfer cancellable at all: marking the coroutine cancelled alone would never be
    // noticed by a purely-blocking sshj call.
    @Volatile
    private var activeTransfer: Closeable? = null

    // write()'s dispatch target (see write() below) - a per-connection single-worker view of
    // Dispatchers.IO over a shared one, so ordering is only guaranteed within this connection's own
    // writes and one session's slow write can't head-of-line block another session's.
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    lateinit var emulator: TerminalEmulator
        private set

    /** Null until [connect] has finished allocating the PTY. */
    val emulatorOrNull: TerminalEmulator?
        get() = if (::emulator.isInitialized) emulator else null

    // Bumped by the reader coroutine on every batch of shell output. The terminal UI collects this
    // to know when to redraw, coalesced to its own frame cadence: see feature/session.
    private val _outputTick = MutableStateFlow(0)
    val outputTick: StateFlow<Int> = _outputTick

    // Set once, before emulator construction, by connect()'s cursorStyle parameter. Like scrollback
    // depth, TerminalEmulator only queries this callback at construction (and on reset()), so
    // changing the setting while a session is open has no live effect on it. @Volatile rather than
    // Compose state: sessionClient runs on whatever thread calls into the emulator.
    @Volatile
    private var cursorStyleOverride: Int? = null

    private val sessionClient =
        object : TerminalSessionClient {
            override fun onTerminalCursorStateChange(state: Boolean) {}

            override fun getTerminalCursorStyle(): Int? = cursorStyleOverride

            override fun logError(tag: String, message: String) {
                Log.e(tag, message)
            }

            override fun logWarn(tag: String, message: String) {
                Log.w(tag, message)
            }

            override fun logInfo(tag: String, message: String) {
                Log.i(tag, message)
            }

            override fun logDebug(tag: String, message: String) {
                Log.d(tag, message)
            }

            override fun logVerbose(tag: String, message: String) {
                Log.v(tag, message)
            }
        }

    /**
     * Connect, verify the host key, authenticate, allocate a PTY and start a shell. Suspends until the
     * shell is ready. [hostKeyVerifier] has no default, because there is no accept-all fallback
     * anywhere in this codebase.
     *
     * [cursorStyle] is applied once, here; see [cursorStyleOverride] for why a later settings change
     * leaves an open session alone. [transcriptRows] goes straight to [TerminalEmulator], which quietly
     * clamps it to `[100, 50000]` and reads `null` as 2000, so an invalid value has to be caught in the
     * settings UI to be caught visibly at all.
     *
     * A resilient session gets [RESILIENT_SESSION_BOOTSTRAP] written to the shell's stdin rather than
     * run as a different exec target, which keeps [shell] a Session.Shell with `changeWindowDimensions`
     * - [Session.Command] has no such method. It also puts the line in the scrollback as though typed,
     * so the fallback to a plain shell is visible.
     *
     * `open`, with [disconnect]/[stopAllForwards]: SessionManagerTest substitutes a fake that never
     * touches a real socket, since driving these for real would need a live SSH server.
     */
    open suspend fun connect(
        host: String,
        port: Int,
        username: String,
        authMethod: AuthMethod,
        hostKeyVerifier: HostKeyVerifier,
        cols: Int,
        rows: Int,
        resilientSession: Boolean = false,
        cursorStyle: Int? = null,
        cursorBlink: Boolean = false,
        transcriptRows: Int? = null,
        hops: List<ProxyHop> = emptyList(),
    ) {
        cursorStyleOverride = cursorStyle
        withContext(Dispatchers.IO) {
            // Connect + authenticate is shared with ScriptRunner's headless capture. Everything
            // below (PTY, shell, reader loop) is interactive-only.
            chainResources = connectChainAndAuthenticate(
                ssh,
                hops,
                host,
                port,
                username,
                authMethod,
                hostKeyVerifier
            )

            val newSession = ssh.startSession()
            session = newSession
            newSession.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
            val newShell = newSession.startShell()
            shell = newShell

            // On Main like every other TerminalEmulator access. Nothing else touches `emulator`
            // until this returns, so construction alone wouldn't race; keeping it here leaves no
            // exception to remember.
            withContext(Dispatchers.Main) {
                emulator = TerminalEmulator(
                    this@SshConnection,
                    cols,
                    rows,
                    0,
                    0,
                    transcriptRows,
                    sessionClient
                )
                // The engine has no blink timer: this only sets whether the cursor should blink.
                // SessionsScreen drives the on/off schedule.
                emulator.setCursorBlinkingEnabled(cursorBlink)
            }

            if (resilientSession) {
                val bootstrap = (RESILIENT_SESSION_BOOTSTRAP + "\n").toByteArray(Charsets.UTF_8)
                newShell.outputStream.write(bootstrap)
                newShell.outputStream.flush()
            }

            readerJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(READ_BUFFER_SIZE)
                val input = newShell.inputStream
                try {
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        // withContext suspends until the Main-thread append completes, so reusing
                        // `buffer` next iteration is safe.
                        withContext(Dispatchers.Main) { emulator.append(buffer, read) }
                        _outputTick.value++
                    }
                    if (!closing.get()) onDisconnected(true)
                } catch (e: IOException) {
                    Log.i(LOG_TAG, "reader stopped: ${e.message}")
                    if (!closing.get()) onDisconnected(false)
                }
            }
        }
    }

    // TerminalOutput.write is called synchronously from UI-thread callbacks but is a plain void
    // method, so the socket write is dispatched here instead of blocking the caller and throwing
    // NetworkOnMainThreadException.
    //
    // writeDispatcher and not bare Dispatchers.IO, because the IO pool gives no FIFO guarantee across
    // independently launched coroutines: two keystrokes a moment apart could write out of order.
    // write() is always called from Main, so calls enqueue in order, and limitedParallelism(1) runs
    // one at a time.
    override fun write(data: ByteArray, offset: Int, count: Int) {
        val copy = data.copyOfRange(offset, offset + count)
        scope.launch(writeDispatcher) {
            val out = shell?.outputStream ?: return@launch
            try {
                out.write(copy)
                out.flush()
            } catch (e: IOException) {
                Log.w(LOG_TAG, "write failed: ${e.message}")
            }
        }
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {}

    // Two callers: TerminalEmulator for an OSC 52 request from the remote app (tmux's
    // `set-clipboard`), and the selection overlay's Copy action. One clipboard write between them.
    override fun onCopyTextToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    // Only the overlay's Paste action calls this; no OSC sequence triggers it from the remote side.
    // The clipboard read lives here so there is one place that knows how to read it, mirroring
    // onCopyTextToClipboard. emulator.paste() goes down the same write path as typed input.
    override fun onPasteTextFromClipboard() {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty() && ::emulator.isInitialized) emulator.paste(text)
    }

    // A plain SharedPreferences read, so no dispatcher hop despite running on Main.
    override fun onBell() {
        when (BellPreferences.get(context)) {
            BellMode.SILENT -> {}
            BellMode.VIBRATE -> vibrateForBell()
            BellMode.NOTIFY -> notifyForBell()
        }
    }

    private fun vibrateForBell() {
        val vibrator =
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                BELL_VIBRATION_MS,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
    }

    // The one call needing POST_NOTIFICATIONS, which MainActivity requests up front. Denied,
    // notify() still doesn't throw on API 33+; it just shows nothing.
    private fun notifyForBell() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(BELL_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                BELL_CHANNEL_ID,
                "Terminal bell",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Alerts when a connected session rings the terminal bell."
            manager.createNotificationChannel(channel)
        }
        val notification =
            NotificationCompat.Builder(context, BELL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_shellwave)
                .setContentTitle("Terminal bell")
                .setContentText("A connected session rang the bell.")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        manager.notify(BELL_NOTIFICATION_ID, notification)
    }

    // An OSC colour change is already visible without anything here: that sequence only arrives as
    // part of a read() the reader loop already ticks for. notifyColorsChanged() below is a separate
    // entry point for this class's own callers.
    override fun onColorsChanged() {}

    /**
     * Forces a redraw after [SessionManager.applyColorScheme] changed this session's live colours.
     * Mutating `emulator.mColors.mCurrentColors` in place is invisible to Compose otherwise.
     */
    fun notifyColorsChanged() {
        _outputTick.value++
    }

    suspend fun resize(cols: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (!::emulator.isInitialized) return
        if (cols == emulator.mColumns && rows == emulator.mRows) return
        // resize() throws below 2x2, and a fold transition can transiently measure a near-zero
        // pane.
        if (cols < 2 || rows < 2) return
        // emulator.resize() mutates TerminalBuffer and must stay on Main. changeWindowDimensions()
        // is a blocking socket write and must not be, or it throws NetworkOnMainThreadException.
        withContext(Dispatchers.Main) {
            emulator.resize(cols, rows, cellWidthPixels, cellHeightPixels)
        }
        // TerminalEmulator wants per-cell pixel size for CSI window-report replies; sshj wants the
        // total, which servers generally ignore in favour of cols/rows. Matching allocatePTY's 0,
        // 0.
        try {
            withContext(Dispatchers.IO) {
                shell?.changeWindowDimensions(cols, rows, 0, 0)
            }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "changeWindowDimensions failed: ${e.message}")
        }
    }

    /**
     * [closing] is set first so the reader loop's own exit, EOF or IOException provoked by the teardown
     * below, doesn't turn around and report itself as a surprise disconnect.
     *
     * Callers reach this from a Compose onClick on the main thread and must not block, and the teardown
     * is blocking socket I/O, so the work goes to `Dispatchers.IO` like [write]'s. Without that,
     * `ssh.disconnect()` throws `NetworkOnMainThreadException`, [runCatching] swallows it, and the
     * socket is never closed: the app drops the session from its UI while the server keeps the TCP
     * connection and its shell alive indefinitely.
     */
    open fun disconnect() {
        closing.set(true)
        readerJob?.cancel()
        scope.launch(Dispatchers.IO) {
            stopAllForwards()
            runCatching { shell?.close() }
            runCatching { session?.close() }
            runCatching { ssh.disconnect() }
            // After `ssh` itself, per ProxyChainResources.disconnect's ordering. A no-op with no
            // hops.
            runCatching { chainResources.disconnect() }
        }
    }

    /**
     * sshj's own [SSHClient.newLocalPortForwarder], whose constructor takes an already-bound
     * ServerSocket, so the only hand-rolled part is the bind.
     *
     * [bindAddress] is caller-resolved: a blank or `"0.0.0.0"` value is already a deliberate choice by
     * the time it arrives. A synchronous bind failure comes back as `Result.failure` rather than being
     * thrown across a coroutine boundary.
     *
     * [LocalPortForwarder.listen] blocks forever once bound, so it gets its own child coroutine on
     * [scope] instead of a leaked raw `Thread`. [onForwardStopped] fires when that loop ends, whether
     * from a deliberate [stopForward] or a genuine `IOException`.
     */
    suspend fun startLocalForward(
        forwardId: Long,
        bindAddress: String,
        bindPort: Int,
        targetHost: String,
        targetPort: Int
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(bindAddress, bindPort))
                val forwarder = ssh.newLocalPortForwarder(
                    Parameters(
                        bindAddress,
                        bindPort,
                        targetHost,
                        targetPort
                    ), serverSocket
                )
                localForwarders[forwardId] = forwarder
                scope.launch(Dispatchers.IO) {
                    try {
                        forwarder.listen()
                        onForwardStopped(forwardId, null)
                    } catch (e: IOException) {
                        Log.w(LOG_TAG, "local forward $forwardId stopped: ${e.message}")
                        onForwardStopped(forwardId, e.message ?: "Forward stopped unexpectedly")
                    } finally {
                        localForwarders.remove(forwardId)
                    }
                }
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * No accept loop here: [RemotePortForwarder.bind] sends the `tcpip-forward` global request, and
     * once acknowledged, incoming `forwarded-tcpip` channels go straight to the
     * [SocketForwardingConnectListener] registered here.
     *
     * `bind`/`cancel` are blocking round-trips, so this runs on Dispatchers.IO too. A server refusing
     * to listen on [bindPort] surfaces as `Result.failure`.
     */
    suspend fun startRemoteForward(
        forwardId: Long,
        bindAddress: String,
        bindPort: Int,
        targetHost: String,
        targetPort: Int
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val forward =
                    ssh.remotePortForwarder.bind(
                        RemotePortForwarder.Forward(bindAddress, bindPort),
                        SocketForwardingConnectListener(InetSocketAddress(targetHost, targetPort)),
                    )
                remoteForwards[forwardId] = forward
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * No `targetHost`/`targetPort`: a SOCKS5 listener has no fixed target, and each accepted
     * connection's destination comes from its own CONNECT request.
     */
    suspend fun startDynamicForward(
        forwardId: Long,
        bindAddress: String,
        bindPort: Int
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(bindAddress, bindPort))
                val forwarder = Socks5Forwarder(ssh, serverSocket, scope)
                dynamicForwarders[forwardId] = forwarder
                scope.launch(Dispatchers.IO) {
                    try {
                        forwarder.listen()
                        onForwardStopped(forwardId, null)
                    } catch (e: IOException) {
                        Log.w(LOG_TAG, "dynamic forward $forwardId stopped: ${e.message}")
                        onForwardStopped(forwardId, e.message ?: "Forward stopped unexpectedly")
                    } finally {
                        dynamicForwarders.remove(forwardId)
                    }
                }
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /** A no-op if [forwardId] isn't currently active. */
    suspend fun stopForward(forwardId: Long) {
        withContext(Dispatchers.IO) {
            localForwarders.remove(forwardId)?.let { runCatching { it.close() } }
            remoteForwards.remove(forwardId)
                ?.let { forward -> runCatching { ssh.remotePortForwarder.cancel(forward) } }
            dynamicForwarders.remove(forwardId)?.let { runCatching { it.close() } }
        }
    }

    /**
     * Called from [disconnect], and separately by [SessionManager.handleDisconnected] the moment an
     * unclean drop is detected, without waiting for the backoff delay: a local [ServerSocket] is
     * independent of this connection's transport, so it stays bound, and its port occupied, until
     * something explicitly closes it.
     *
     * Blocking and not suspend, so it is callable from a context already on `Dispatchers.IO` without
     * another hop. [RemotePortForwarder.cancel] on a dead transport is expected to throw; the point
     * here is only to forget about it locally.
     */
    open fun stopAllForwards() {
        localForwarders.keys.toList()
            .forEach { id -> localForwarders.remove(id)?.let { runCatching { it.close() } } }
        remoteForwards.keys.toList().forEach { id ->
            remoteForwards.remove(id)
                ?.let { forward -> runCatching { ssh.remotePortForwarder.cancel(forward) } }
        }
        dynamicForwarders.keys.toList()
            .forEach { id -> dynamicForwarders.remove(id)?.let { runCatching { it.close() } } }
    }

    /**
     * Reuses this connection's already-authenticated [ssh]: no second connection, no re-auth, no
     * separate credential path, and it works unchanged through a ProxyJump chain, an SFTP channel being
     * just another channel over the same transport.
     *
     * sshj's real transfer clients only, never a shell/`cat` hack. SFTP is preferred, with SCP as the
     * fallback when the server has no SFTP subsystem (SSHClient.newSFTPClient throwing is the only
     * signal sshj gives for that). Both implement [FileTransfer], so one code path serves either.
     */
    private fun openFileTransfer(): Pair<FileTransfer, Closeable?> =
        try {
            val sftp = ssh.newSFTPClient()
            sftp.fileTransfer to sftp
        } catch (e: IOException) {
            Log.i(LOG_TAG, "SFTP unavailable (${e.message}), falling back to SCP")
            ssh.newSCPFileTransfer() to null
        }

    /** Single-file only, so `TransferListener.directory` just recurses into itself. */
    private fun fileTransferListener(onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit): TransferListener =
        object : TransferListener {
            override fun directory(name: String): TransferListener = this

            override fun file(name: String, size: Long): StreamCopier.Listener =
                StreamCopier.Listener { transferred -> onProgress(transferred, size) }
        }

    /**
     * Only answerable via SFTP's `stat`, so an unavailable subsystem is `Result.failure` and not a
     * guessed `false`: a caller must never mistake "couldn't check" for "doesn't exist" and clobber
     * something it never looked at.
     */
    suspend fun remoteFileExists(remotePath: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                ssh.newSFTPClient()
                    .use { sftp -> Result.success(sftp.statExistence(remotePath) != null) }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * SFTP-only, and [Result.failure] is a real outcome: SCP has no listing operation, so the caller
     * degrades to a typed path and says so instead of showing an empty directory, which would claim
     * something different.
     *
     * A short-lived client per listing, like [remoteFileExists]: [cancelActiveTransfer] closes
     * [activeTransfer] out from under its user, so a shared client would let an unrelated cancelled
     * transfer kill a listing.
     *
     * SFTP has no tilde expansion, so a typed `~/logs` becomes `./logs` for `canonicalize`, which runs
     * before `ls` so [RemoteListing.path] is absolute and breadcrumb arithmetic works on a real path.
     * Symlinks are followed, since `ls` reports the link's own type and a symlinked directory would
     * otherwise list as an untappable file.
     */
    suspend fun listRemoteDirectory(remotePath: String): Result<RemoteListing> =
        withContext(Dispatchers.IO) {
            try {
                ssh.newSFTPClient().use { sftp ->
                    val resolved = sftp.canonicalize(expandTildeForSftp(remotePath))
                    val entries =
                        sftp.ls(resolved).map { info ->
                            RemoteEntry(
                                name = info.name,
                                path = info.path,
                                isDirectory = info.isDirectory || (info.attributes.type == FileMode.Type.SYMLINK && runCatching {
                                    sftp.stat(
                                        info.path
                                    ).type == FileMode.Type.DIRECTORY
                                }.getOrDefault(false)),
                            )
                        }
                    Result.success(RemoteListing(resolved, entries))
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * Reusing the live connection means no second authentication (so a run still works after a windowed
     * biometric credential's window has closed), no second host-key decision, and no ambiguity about
     * which machine answered, which a fresh dial to a load-balanced address cannot promise. Nothing
     * here can accept a host key, because nothing here verifies one.
     *
     * It is not the session's shell, though. An `exec` channel starts clean: no working directory, no
     * shell variables, no tmux context. That is the difference from SEND_TO_CURRENT, which types into
     * the live shell and inherits all of it, and the picker says so per row.
     *
     * [command] runs verbatim, so it arrives substituted and quoted.
     */
    suspend fun execCapture(command: String): CaptureResult =
        withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                    execAndCollect(ssh, command)
                } ?: CaptureResult(
                    "",
                    "",
                    false,
                    false,
                    null,
                    error = "Timed out after ${CAPTURE_TIMEOUT_MS / 1000}s"
                )
            } catch (e: Exception) {
                // Most likely the session dropped between the menu tap and this call. Report it
                // like every other capture failure; the caller has a result sheet to fill either
                // way.
                CaptureResult(
                    "",
                    "",
                    false,
                    false,
                    null,
                    error = e.message ?: "Could not run the script on this session"
                )
            }
        }

    /**
     * [destination] is a SAF document `Uri` the caller already holds write access to. Cancellable via
     * [cancelActiveTransfer], best-effort on the SCP fallback path.
     *
     * [onProgress] fires from this IO thread with sshj's cumulative byte count and the remote size,
     * which comes free from the `stat` sshj already does before copying a byte.
     *
     * That size is compared against the final progress report, and a short transfer is Result.failure
     * instead of a silently accepted partial file. "No exception thrown" is not treated as proof.
     */
    suspend fun downloadFile(
        remotePath: String,
        destination: Uri,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            var lastReported = 0L
            var expectedSize = -1L
            val listener =
                fileTransferListener { transferred, total ->
                    lastReported = transferred
                    expectedSize = total
                    onProgress(transferred, total)
                }
            try {
                val (xfer, closeable) = openFileTransfer()
                activeTransfer = closeable
                try {
                    xfer.transferListener = listener
                    xfer.download(remotePath, UriDestFile(context, destination))
                } finally {
                    runCatching { closeable?.close() }
                    activeTransfer = null
                }
                if (expectedSize >= 0 && lastReported != expectedSize) {
                    Result.failure(IOException("Downloaded $lastReported bytes but the remote file is $expectedSize bytes - the transfer looks incomplete"))
                } else {
                    Result.success(lastReported)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Broad, beyond IOException: pointing this at a remote directory hits sshj's
                // LocalDestFile.getTargetDirectory, which InMemoryDestFile throws a bare
                // AssertionError from.
                Result.failure(e)
            }
        }

    /**
     * Verification runs the other way round from [downloadFile]: there is no upfront remote size to
     * check against, the destination not existing yet, so this re-`stat`s [remotePath] afterwards and
     * compares it to [source]'s length. A mismatch, "couldn't re-stat" included, is a failure.
     *
     * No opinion on overwrite. The caller resolves that via [remoteFileExists] and a confirmation
     * dialog before ever getting here.
     */
    suspend fun uploadFile(
        remotePath: String,
        source: Uri,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            var lastReported = 0L
            val listener = fileTransferListener { transferred, total ->
                lastReported = transferred; onProgress(
                transferred,
                total
            )
            }
            try {
                val localSource = UriSourceFile(context, source)
                val expectedSize = localSource.length
                val (xfer, closeable) = openFileTransfer()
                activeTransfer = closeable
                try {
                    xfer.transferListener = listener
                    xfer.upload(localSource, remotePath)
                } finally {
                    runCatching { closeable?.close() }
                    activeTransfer = null
                }
                val remoteSize =
                    try {
                        ssh.newSFTPClient().use { it.stat(remotePath).size }
                    } catch (e: IOException) {
                        null
                    }
                when {
                    remoteSize == null -> Result.success(lastReported)
                    expectedSize >= 0 && remoteSize != expectedSize ->
                        Result.failure(IOException("Uploaded, but the server now reports $remoteSize bytes for a $expectedSize-byte file - the transfer looks incomplete"))

                    else -> Result.success(lastReported)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Closes [activeTransfer], the SFTP client actually doing the copy, which aborts its blocked
     * read/write and unwinds the suspended call as a failure the caller recognises. A no-op on the SCP
     * fallback path, where sshj gives no handle to interrupt early.
     */
    fun cancelActiveTransfer() {
        activeTransfer?.let { runCatching { it.close() } }
    }
}
