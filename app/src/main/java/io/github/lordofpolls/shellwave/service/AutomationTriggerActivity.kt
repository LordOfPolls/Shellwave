package io.github.lordofpolls.shellwave.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.lordofpolls.shellwave.core.prefs.AutomationPreferences

/** Tasker's "Send Intent" with Target = Activity, MacroDroid, Automate and `adb shell am start -a` all reach this the same way. */
const val ACTION_RUN_SCRIPT = "io.github.lordofpolls.shellwave.action.RUN_SCRIPT"

/** The shared secret from Settings. The fully-qualified form is what goes into Tasker's extras field. */
const val EXTRA_TOKEN = "io.github.lordofpolls.shellwave.extra.TOKEN"

/**
 * The one component in this app another app can address. It validates, hands off to
 * ScriptTriggerService, and finishes without drawing anything.
 *
 * An activity instead of the obvious `BroadcastReceiver`, because a receiver cannot do the job:
 * since Android 12 a background app may not start a foreground service, and an ordinary broadcast
 * from another app is not on the exemption list, so the run would die at `startForeground`.
 * Starting an activity puts this process in the foreground instead. The price is that the caller
 * must be able to start an activity from the background; Tasker, MacroDroid, Automate and `adb` all
 * can.
 *
 * Exporting [ScriptTriggerService] directly would have been one manifest attribute and wrong: an
 * exported service can be addressed by component name with no action, so an action-keyed token
 * check is one a caller skips by omitting the action. With the service unexported, "this came from
 * outside" is a property of which component was reachable, not of an extra the caller supplies.
 *
 * The token gate runs before the id is parsed or the database read. Past it, an automation trigger
 * is an ordinary background trigger, with [backgroundTriggerRefusal] and the vault and host-key
 * refusals unchanged. A run from Tasker is recorded exactly like a widget run.
 *
 * Refusals toast and not fail silently: a silent refusal looks like a task that never fired, and
 * whoever debugs that starts switching safeguards off. Nothing is given away that watching for the
 * run notification would not already reveal.
 */
class AutomationTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val refusal = handle(intent)
        if (refusal != null) Toast.makeText(this, refusal, Toast.LENGTH_LONG).show()
        // No UI, so it must not reach the back stack or Recents on any path; the manifest's
        // noHistory/excludeFromRecents say the same to the system.
        finish()
    }

    /** The message to show, or null once the run has been handed off. */
    private fun handle(intent: Intent?): String? {
        if (intent == null || intent.action != ACTION_RUN_SCRIPT) {
            return "Unrecognised request. Use the action $ACTION_RUN_SCRIPT."
        }
        AutomationPreferences.refusalFor(this, intent.getStringExtra(EXTRA_TOKEN))
            ?.let { return it }
        val scriptId = scriptIdFrom(intent)
            ?: return "No script id given. Add a \"$EXTRA_SCRIPT_ID\" extra with the script's id (shown when you edit it)."
        ScriptTriggerService.startFromAutomation(this, scriptId)
        return null
    }
}

/**
 * The script id from [intent], accepting a `long`, an `int`, or a string of digits.
 *
 * The string form is Tasker's: its "Send Intent" extras are typed as `key:value` text and it picks
 * the type, so the field that arrives as a `long` from `am start --el` arrives as a [String] from a
 * Tasker task. Rejecting it would break the integration this path was built for, with an error
 * about a missing extra that is visibly present.
 *
 * Negative ids are rejected here because `ScriptTriggerService` reads a negative id as "no id" and
 * stops without a toast or a notification.
 */
internal fun scriptIdFrom(intent: Intent): Long? {
    val fromLong = intent.getLongExtra(EXTRA_SCRIPT_ID, -1L)
    if (fromLong >= 0) return fromLong
    // Bundle keys an `int` extra as a different type, so getLongExtra does not see it: and
    // `am start --ei` is the flag people reach for first.
    val fromInt = intent.getIntExtra(EXTRA_SCRIPT_ID, -1)
    if (fromInt >= 0) return fromInt.toLong()
    val fromString = intent.getStringExtra(EXTRA_SCRIPT_ID)?.trim()?.toLongOrNull()
    return fromString?.takeIf { it >= 0 }
}
