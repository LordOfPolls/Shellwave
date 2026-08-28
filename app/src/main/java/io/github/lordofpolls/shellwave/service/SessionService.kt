package io.github.lordofpolls.shellwave.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.lordofpolls.shellwave.MainActivity
import io.github.lordofpolls.shellwave.R
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SessionSummary
import io.github.lordofpolls.shellwave.ssh.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CHANNEL_ID = "sessions"
private const val NOTIFICATION_ID = 1
private const val ACTION_DISCONNECT_ALL = "io.github.lordofpolls.shellwave.action.DISCONNECT_ALL"

/**
 * Keeps [SessionManager]'s connections alive while the app is backgrounded. `specialUse`, not
 * `dataSync`: this isn't syncing data on a schedule, it's holding open interactive connections the
 * user explicitly started and is still using - `dataSync`'s Android 15 time cap would kill a long
 * `tail -f` or an idle-but-open shell for no reason. The manifest's
 * `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` is the Play-review justification for that choice.
 *
 * Not a bound service: SessionManager is a Hilt `@Singleton` the Activity injects directly, so
 * there is no IPC boundary for a `Binder` to cross: this and the Activity are two observers of the
 * same in-process object. The job here is holding [Service.startForeground], which keeps the
 * process alive under memory pressure and Doze, and keeping the notification's session state and
 * "Disconnect all" in step with `SessionManager.summaries`. It starts itself from
 * [SessionManager.openSession] and stops once nothing is left to keep alive.
 *
 * [androidx.core.app.ServiceCompat.startForeground] avoids API-level branching for the
 * `foregroundServiceType` overload, and runs whether or not notification permission was granted,
 * since the sessions work either way. `startForeground` with a [Notification] does not itself
 * require `POST_NOTIFICATIONS`; only [android.app.NotificationManager.notify] does, and this class
 * never calls it.
 */
@AndroidEntryPoint
class SessionService : Service() {

    @Inject
    lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Started via ContextCompat.startForegroundService by SessionManager.openSession, which
        // publishes the new session's entry before starting this service - so summaries.value is
        // already non-empty by the time this collector's first emission runs. startForeground()
        // must run within seconds of the service starting regardless, so build the first
        // notification straight from that current value instead of waiting on the flow.
        startForeground(sessionManager.summaries.value)
        scope.launch {
            sessionManager.summaries.collect { summaries ->
                if (summaries.isEmpty()) {
                    stopSelf()
                } else {
                    startForeground(summaries)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) sessionManager.disconnectAll()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground(summaries: List<SessionSummary>) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(summaries),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun buildNotification(summaries: List<SessionSummary>): Notification {
        // FLAG_ACTIVITY_NEW_TASK because this PendingIntent is built from a Service context, not an
        // Activity; MainActivity's launchMode="singleTop" (see the manifest) plus this flag means
        // tapping it redelivers to the already-running instance via onNewIntent() rather than
        // stacking a second one: see MainActivity.handleIntent.
        val openSessionsIntent =
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SESSIONS, true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openApp =
            PendingIntent.getActivity(this, 0, openSessionsIntent, PendingIntent.FLAG_IMMUTABLE)
        val disconnectAll =
            PendingIntent.getService(
                this,
                0,
                Intent(this, SessionService::class.java).setAction(ACTION_DISCONNECT_ALL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val sessions =
            summaries.map { s ->
                NotificationSession(
                    s.label,
                    s.status,
                    s.tunnels.count { it.state == TunnelState.RUNNING },
                )
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shellwave)
            .setContentTitle(notificationTitle(sessions))
            .setContentText(notificationText(sessions))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect all", disconnectAll)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(CHANNEL_ID, "SSH sessions", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shows the state of SSH sessions running in the background."
        manager.createNotificationChannel(channel)
    }
}

internal data class NotificationSession(
    val label: String,
    val status: SessionStatus,
    val runningTunnels: Int,
)


internal fun notificationTitle(sessions: List<NotificationSession>): String {
    if (sessions.isEmpty()) return "No sessions"
    val total = sessions.size
    val connected = sessions.count { it.status == SessionStatus.CONNECTED }
    val runningTunnels = sessions.sumOf { it.runningTunnels }
    val tunnelSuffix =
        if (runningTunnels > 0) " · $runningTunnels tunnel${if (runningTunnels == 1) "" else "s"}" else ""
    return when {
        connected == total ->
            (if (total == 1) "1 session active" else "$total sessions active") + tunnelSuffix
        connected > 0 -> "$connected of $total sessions active$tunnelSuffix"
        sessions.any { it.status == SessionStatus.RECONNECTING } ->
            if (total == 1) "Reconnecting…" else "Reconnecting $total sessions…"
        sessions.any { it.status == SessionStatus.CONNECTING } ->
            if (total == 1) "Connecting…" else "Connecting $total sessions…"
        sessions.all { it.status == SessionStatus.FAILED } ->
            if (total == 1) "Connection failed" else "$total sessions failed"
        else -> if (total == 1) "Session disconnected" else "$total sessions disconnected"
    }
}

internal fun notificationText(sessions: List<NotificationSession>): String {
    val uniform = sessions.distinctBy { it.status }.size <= 1
    return sessions.joinToString(", ") { s ->
        val note = if (uniform) null else statusNote(s.status)
        if (note == null) s.label else "${s.label} ($note)"
    }
}

private fun statusNote(status: SessionStatus): String? =
    when (status) {
        SessionStatus.CONNECTED -> null
        SessionStatus.CONNECTING -> "connecting"
        SessionStatus.RECONNECTING -> "reconnecting"
        SessionStatus.DISCONNECTED -> "disconnected"
        SessionStatus.FAILED -> "failed"
    }
