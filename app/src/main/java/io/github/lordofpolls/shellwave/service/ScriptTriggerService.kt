package io.github.lordofpolls.shellwave.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.lordofpolls.shellwave.MainActivity
import io.github.lordofpolls.shellwave.R
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.ScriptDao
import io.github.lordofpolls.shellwave.core.db.dao.ScriptRunDao
import io.github.lordofpolls.shellwave.core.db.entities.ScriptRunEntity
import io.github.lordofpolls.shellwave.core.util.substituteParams
import io.github.lordofpolls.shellwave.feature.scripts.mark
import io.github.lordofpolls.shellwave.feature.scripts.runOutcomeMessage
import io.github.lordofpolls.shellwave.service.ScriptTriggerService.Companion.start
import io.github.lordofpolls.shellwave.ssh.ScriptRunner
import io.github.lordofpolls.shellwave.ssh.resolveProxyHops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val CHANNEL_ID = "background_script_runs"
private const val RUNNING_NOTIFICATION_ID = 100
internal const val EXTRA_SCRIPT_ID = "io.github.lordofpolls.shellwave.extra.SCRIPT_ID"

/**
 * Set only by AutomationTriggerActivity, and unforgeable from outside because this service stays
 * `android:exported="false"`; see that class for why the exported surface is an activity and not
 * this. Its presence turns on the one extra refusal in [backgroundTriggerRefusal]; its absence must
 * therefore mean "in-process trigger", which is exactly what an unexported service can guarantee.
 */
private const val EXTRA_FROM_AUTOMATION = "io.github.lordofpolls.shellwave.extra.FROM_AUTOMATION"

/**
 * Set only by `WidgetTrampolineActivity`, to the single-use token it stashed this run's credentials
 * under. Absent, every credential falls through to the ordinary `activity = null` refusal. Ignored
 * outright under [EXTRA_FROM_AUTOMATION]: the exported path has no legitimate way to know it.
 */
private const val EXTRA_AUTH_TOKEN = "io.github.lordofpolls.shellwave.extra.AUTH_TOKEN"

/**
 * `stopSelf(startId)` only stops the service for the most recently delivered startId, so a fast run
 * finishing after a slower one started used to tear down the shared scope and kill it.
 */
class InFlightRunCounter {
    private val inFlight = AtomicInteger(0)

    fun runStarted() {
        inFlight.incrementAndGet()
    }

    fun runFinished(): Boolean = inFlight.decrementAndGet() == 0

    fun isIdle(): Boolean = inFlight.get() == 0

    fun count(): Int = inFlight.get()
}

/**
 * Every background trigger - widget button, Quick Settings tile, app shortcut - funnels its script
 * run through here. `startForegroundService` plus this service keeps the
 * widget-tap-while-the-app-is-closed case legal on Android 14+: the tap fires a `PendingIntent`,
 * which counts as user interaction, which permits the foreground service start.
 *
 * Runs go to runCaptureBackground, never `runCapture`, so no host-key or 2FA prompt can be answered
 * from here. [backgroundTriggerRefusal] adds the refusals that can be decided from the script row:
 * capture mode only, no `{{param}}`s, a fixed target host, and `allowAutomation` when another app
 * is the trigger - each because there is nobody here to answer a prompt.
 *
 * This service stays `android:exported="false"`. [AutomationTriggerActivity] is the exported
 * surface; it settles the app-wide switch and the token before marking the intent so that last
 * per-script gate applies.
 *
 * Biometric-gated credentials fail one layer down, by default: `CredentialVault.resolve` is called
 * with `activity = null`, and the [IllegalStateException] it throws is caught below and reported like
 * any other refusal. [resolveProxyHops] resolves each jump host's credential the same way, so a
 * bastion refuses exactly as the target would, and a keyboard-interactive hop is refused before
 * anything connects.
 *
 * The widget is the one trigger that can avoid that refusal, and only via
 * `WidgetTrampolineActivity`, which prompts for real and stashes the results under [EXTRA_AUTH_TOKEN].
 * This service still never prompts and never holds an activity; it only forwards that token. See
 * `TriggerAuthStash`'s class doc for the contract that back door keeps.
 */
@AndroidEntryPoint
class ScriptTriggerService : Service() {

    @Inject
    lateinit var scriptDao: ScriptDao

    @Inject
    lateinit var hostDao: HostDao

    @Inject
    lateinit var credentialVault: CredentialVault

    @Inject
    lateinit var scriptRunner: ScriptRunner

    @Inject
    lateinit var scriptRunDao: ScriptRunDao

    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private val inFlightRuns = InFlightRunCounter()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scriptId = intent?.getLongExtra(EXTRA_SCRIPT_ID, -1L) ?: -1L
        val fromAutomation = intent?.getBooleanExtra(EXTRA_FROM_AUTOMATION, false) ?: false
        // See EXTRA_AUTH_TOKEN's doc for why fromAutomation suppresses this even if somehow present.
        val trigger = intent?.getStringExtra(EXTRA_AUTH_TOKEN)
            ?.takeUnless { fromAutomation }
            ?.let { CredentialVault.TriggerAuth(scriptId, it) }
        // startForeground() must run within seconds of the service starting, whether or not the
        // scriptId turns out to be valid; so this happens unconditionally, before the id is even
        // checked, exactly like SessionService's onCreate reasoning for the same requirement.
        ServiceCompat.startForeground(
            this,
            RUNNING_NOTIFICATION_ID,
            buildRunningNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        if (scriptId < 0) {
            // Never counted, so stopping is only safe while nothing else is running.
            if (inFlightRuns.isIdle()) stopSelf()
            return START_NOT_STICKY
        }
        inFlightRuns.runStarted()
        scope.launch {
            runTriggeredScript(scriptId, fromAutomation, trigger)
            if (inFlightRuns.runFinished()) {
                stopSelf()
            } else {
                updateRunningNotification(null)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runTriggeredScript(
        scriptId: Long,
        fromAutomation: Boolean,
        trigger: CredentialVault.TriggerAuth?,
    ) {
        val script = scriptDao.getById(scriptId)
        if (script == null) {
            // Same message whether a widget outlived its script or an automation task named an id
            // that never existed: distinguishing them would tell an outside app which ids are real.
            notifyOutcome(
                "Script not found",
                "No script has that id - it may have been deleted."
            )
            return
        }
        // The credential and host-key refusals are not decidable from a row, so they live where the
        // keys are, below.
        backgroundTriggerRefusal(script, fromAutomation)?.let {
            notifyOutcome(script.name, it)
            return
        }
        // The run is going to happen, so the notification can stop being generic.
        updateRunningNotification(script.name)
        Toast.makeText(this, "Running \"${script.name}\"\u2026", Toast.LENGTH_SHORT).show()

        // Non-null once the refusals above have passed - the last of them is exactly this check.
        val hostId = script.targetHostId ?: return
        val host = hostDao.getById(hostId)
        if (host == null) {
            notifyOutcome(script.name, "The host this script targets no longer exists.")
            return
        }

        val authMethod =
            try {
                credentialVault.resolve(host.credentialId, activity = null, trigger = trigger)
            } catch (e: Exception) {
                notifyOutcome(
                    script.name,
                    e.message
                        ?: "Could not unlock this host's saved credential without opening the app."
                )
                return
            }
        // A cycle, a dangling jump host, or a hop needing a biometric prompt all surface as a clear
        // notification and not this service hanging or crashing. A hop needing a
        // keyboard-interactive prompt is not rejected here - runCaptureBackground checks that.
        val hops =
            try {
                resolveProxyHops(host, hostDao, credentialVault, activity = null, trigger = trigger)
            } catch (e: Exception) {
                notifyOutcome(
                    script.name,
                    e.message ?: "Could not resolve this host's proxy jump chain."
                )
                return
            }

        val command = substituteParams(script.snippet, emptyMap())
        val startedAt = System.currentTimeMillis()
        val result = scriptRunner.runCaptureBackground(
            "Script: ${script.name}",
            host.hostname,
            host.port,
            host.username,
            authMethod,
            command,
            hops
        )
        val finishedAt = System.currentTimeMillis()

        if (result.error != null) {
            notifyOutcome(script.name, result.error)
            return
        }

        val stdout = mark(result.stdout, result.stdoutTruncated)
        val stderr = mark(result.stderr, result.stderrTruncated)
        val entity =
            ScriptRunEntity(
                scriptId = script.id,
                startedAt = startedAt,
                finishedAt = finishedAt,
                exitStatus = result.exitStatus,
                stdout = stdout,
                stderr = stderr,
            )
        scriptRunDao.insert(entity)

        notifyOutcome(script.name, runOutcomeMessage(result.exitStatus, stdout, stderr))
    }

    private fun notifyOutcome(scriptName: String, message: String) {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_shellwave)
                .setContentTitle(scriptName)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        // NotificationManager.notify() never throws for a missing POST_NOTIFICATIONS grant on API
        // 33+, it just silently doesn't show, so there is nothing to re-check here.
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(nextResultNotificationId.incrementAndGet(), notification)
    }

    /**
     * [scriptName] is null for the very first post, because [onStartCommand] must call
     * `startForeground` within seconds and the name costs a database read. Being visible immediately
     * matters more, so the notification goes up generic and [updateRunningNotification] specialises it a
     * moment later. Also null with several runs in flight, where naming one would misrepresent the rest.
     */
    private fun buildRunningNotification(scriptName: String? = null, runningCount: Int = 1): Notification {
        val title = when {
            scriptName != null -> "Running \"$scriptName\"…"
            runningCount > 1 -> "Running $runningCount scripts…"
            else -> "Running script…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shellwave)
            .setContentTitle(title)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Concurrent runs share one notification id, so past the first the banner shows a count, not a name. */
    private fun updateRunningNotification(scriptName: String?) {
        val count = inFlightRuns.count()
        val notification =
            if (scriptName != null && count <= 1) buildRunningNotification(scriptName)
            else buildRunningNotification(runningCount = count)
        getSystemService(NotificationManager::class.java)?.notify(RUNNING_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Widget/tile/shortcut script runs",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description =
            "Results of scripts run from the launcher widget, a Quick Settings tile, or an app shortcut."
        manager.createNotificationChannel(channel)
    }

    companion object {
        // Distinct per-run notification ids so several background runs in quick succession each
        // keep their own result notification and not one overwriting another:
        // RUNNING_NOTIFICATION_ID itself is reused per-run (there is only ever one "running" state
        // at a time per process).
        private val nextResultNotificationId = AtomicInteger(RUNNING_NOTIFICATION_ID + 1)

        /** The explicit intent that runs [scriptId], shared by every `start*` variant below. */
        fun intentFor(context: Context, scriptId: Long): Intent =
            Intent(context, ScriptTriggerService::class.java).putExtra(EXTRA_SCRIPT_ID, scriptId)

        /**
         * A background capture run with no `TriggerAuth`: the QS tile, and the trampoline's fallback for
         * anything that fails before it can mint one, so the refusal notification is written once, here.
         */
        fun start(context: Context, scriptId: Long) {
            ContextCompat.startForegroundService(context, intentFor(context, scriptId))
        }

        /**
         * Separate from [start] instead of a boolean parameter on it so that every caller has to say which
         * one it is: the marker this adds enables the per-script [ScriptEntity.allowAutomation] gate, and a
         * default-valued parameter is the kind of thing a future caller silently inherits the safe-looking
         * side of.
         *
         * Only `AutomationTriggerActivity` calls this, and only after the app-wide switch and the token
         * have both passed.
         */
        fun startFromAutomation(context: Context, scriptId: Long) {
            ContextCompat.startForegroundService(
                context,
                intentFor(context, scriptId).putExtra(EXTRA_FROM_AUTOMATION, true)
            )
        }

        /** Called only from `WidgetTrampolineActivity`, only once every credential resolved and stashed. */
        fun startFromTrampoline(context: Context, scriptId: Long, token: String) {
            ContextCompat.startForegroundService(
                context,
                intentFor(context, scriptId).putExtra(EXTRA_AUTH_TOKEN, token)
            )
        }
    }
}
