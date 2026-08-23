package io.github.lordofpolls.shellwave.feature.glance

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.lordofpolls.shellwave.MainActivity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode

private const val MAX_SCRIPT_SHORTCUTS = 2
private const val MAX_HOST_SHORTCUTS = 2
private const val SCRIPT_SHORTCUT_PREFIX = "script-"
private const val HOST_SHORTCUT_PREFIX = "host-"

/**
 * Unlike the widget and Quick Settings tile, a shortcut launches [MainActivity] itself - a launcher
 * shortcut's intent must resolve to an activity, and there is no platform-supported way to point
 * one at a service. So tapping one is a foreground trigger: [MainActivity.handleIntent] runs the
 * script through the same paths a tap inside the app uses, with real TOFU/mismatch/2FA dialogs and
 * param prompts, because the app is on screen to show them. Not routed through
 * `ScriptTriggerService`'s background-safe path, which exists for triggers with no UI.
 */
fun updateDynamicShortcuts(context: Context, scripts: List<ScriptEntity>, hosts: List<HostEntity>) {
    val icon = IconCompat.createWithResource(context, context.applicationInfo.icon)

    val scriptShortcuts =
        scripts
            // Capture-only, but not also host-only, unlike the widget's filter: a shortcut lands in
            // MainActivity, so an "ask each run" script gets the same host picker it would get from
            // inside the app.
            .filter { runCatching { ScriptMode.valueOf(it.mode) }.getOrNull() == ScriptMode.CAPTURE }
            .take(MAX_SCRIPT_SHORTCUTS)
            .map { script ->
                val intent =
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(MainActivity.EXTRA_RUN_SCRIPT_ID, script.id)
                ShortcutInfoCompat.Builder(context, "$SCRIPT_SHORTCUT_PREFIX${script.id}")
                    .setShortLabel(script.name)
                    .setIcon(icon)
                    .setIntent(intent)
                    .build()
            }

    val hostShortcuts =
        hosts.take(MAX_HOST_SHORTCUTS).map { host ->
            val label = host.label ?: host.hostname
            val intent =
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_OPEN_HOST_ID, host.id)
            ShortcutInfoCompat.Builder(context, "$HOST_SHORTCUT_PREFIX${host.id}")
                .setShortLabel(label)
                .setIcon(icon)
                .setIntent(intent)
                .build()
        }

    // setDynamicShortcuts replaces the whole set, so both lists have to be passed together or a
    // change to one clobbers the other.
    ShortcutManagerCompat.setDynamicShortcuts(context, scriptShortcuts + hostShortcuts)
}
