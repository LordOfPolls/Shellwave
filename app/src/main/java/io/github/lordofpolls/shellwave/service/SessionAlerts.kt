package io.github.lordofpolls.shellwave.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lordofpolls.shellwave.MainActivity
import io.github.lordofpolls.shellwave.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "session_alerts"

// Clear of the ids SessionService (1), the terminal bell (2) and ScriptTriggerService (100+) post.
private const val NOTIFICATION_ID_BASE = 10_000

private const val RECOVERY_TIMEOUT_MS = 15_000L

/**
 * Announces an unclean drop, and the reconnect that answers it. A banner when the app is
 * backgrounded - an Android 12+ toast from a background app is dropped silently - and a toast when
 * it isn't, where a banner over the user's own terminal would be noise.
 */
@Singleton
open class SessionAlerts @Inject constructor(@ApplicationContext private val context: Context) {

    // Toast and ProcessLifecycleOwner are both main-thread-only; callers are on whatever thread
    // noticed the drop.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val alerted = ConcurrentHashMap.newKeySet<Long>()

    // open: SessionManagerTest substitutes a no-op subclass, since these post real notifications
    // through Dispatchers.Main, which has no JVM-test-friendly implementation in this codebase (no
    // Robolectric, no kotlinx-coroutines-test).
    open fun dropped(sessionId: Long, label: String) {
        if (!alerted.add(sessionId)) return
        alert(
            sessionId,
            title = "$label disconnected",
            body = "Connection lost - reconnecting…",
            autoDismissMs = null,
        )
    }

    open fun reconnected(sessionId: Long, label: String) {
        if (!alerted.remove(sessionId)) return
        alert(
            sessionId,
            title = "$label reconnected",
            body = "The session is live again.",
            autoDismissMs = RECOVERY_TIMEOUT_MS,
        )
    }

    open fun forget(sessionId: Long) {
        alerted.remove(sessionId)
        scope.launch {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(notificationId(sessionId))
        }
    }

    private fun alert(sessionId: Long, title: String, body: String, autoDismissMs: Long?) {
        scope.launch {
            val manager =
                context.getSystemService(NotificationManager::class.java) ?: return@launch
            val id = notificationId(sessionId)
            if (ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
            ) {
                manager.cancel(id)
                Toast.makeText(context, title, Toast.LENGTH_LONG).show()
                return@launch
            }
            ensureChannel(manager)
            manager.notify(id, build(title, body, autoDismissMs, id))
        }
    }

    private fun build(title: String, body: String, autoDismissMs: Long?, id: Int) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shellwave)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openSessions(id))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { autoDismissMs?.let { setTimeoutAfter(it) } }
            .build()

    private fun openSessions(id: Int): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SESSIONS, true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(CHANNEL_ID, "Session alerts", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Alerts when a session drops and when it reconnects."
        manager.createNotificationChannel(channel)
    }

    private fun notificationId(sessionId: Long) = (NOTIFICATION_ID_BASE + sessionId).toInt()
}
