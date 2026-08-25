package io.github.lordofpolls.shellwave.feature.settings

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lordofpolls.shellwave.core.db.ShellwaveDatabase
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.prefs.AppearancePreferences
import io.github.lordofpolls.shellwave.core.prefs.AutomationPreferences
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityPreferences
import io.github.lordofpolls.shellwave.core.prefs.SessionLayoutPreferences
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The export format's own version, not the database's. */
const val CONFIG_EXPORT_VERSION = 1

private const val SECRETS_NOTE =
    "Passwords, private keys and passphrases are deliberately not in this file. They stay sealed " +
        "in this device's Android Keystore, and the sealed bytes could not be opened on another " +
        "device anyway."

/**
 * The user's configuration as one JSON document.
 *
 * An allowlist of named fields, never a dump of every table and column: `credentials` rows carry
 * the sealed password, private key and passphrase, so a column added later stays out of the export
 * until someone decides it belongs. `script_runs` is left out entirely - a captured run holds
 * whatever the command printed.
 */
fun buildConfigExport(
    hosts: List<HostEntity>,
    credentials: List<CredentialEntity>,
    portForwards: List<PortForwardEntity>,
    scripts: List<ScriptEntity>,
    terminalProfiles: List<TerminalProfileEntity>,
    colorSchemes: List<ColorSchemeEntity>,
    keyBarLayouts: List<KeyBarLayoutEntity>,
    settings: Map<String, Any?>,
    exportedAt: Instant,
): String =
    jsonOf(
        "format" to "shellwave-config",
        "version" to CONFIG_EXPORT_VERSION,
        "exportedAt" to exportedAt.toString(),
        "note" to SECRETS_NOTE,
        "settings" to jsonOf(*settings.toList().toTypedArray()),
        "hosts" to hosts.map { host ->
            jsonOf(
                "id" to host.id,
                "label" to host.label,
                "hostname" to host.hostname,
                "port" to host.port,
                "username" to host.username,
                "credentialId" to host.credentialId,
                "resilientSession" to host.resilientSession,
                "terminalProfileId" to host.terminalProfileId,
                "colorSchemeId" to host.colorSchemeId,
                "keyBarLayoutId" to host.keyBarLayoutId,
                "proxyJumpHostId" to host.proxyJumpHostId,
                "macAddress" to host.macAddress,
                "createdAt" to host.createdAt,
                "lastConnectedAt" to host.lastConnectedAt,
            )
        },
        // Identity only. The sealed columns and their IVs must never be listed here.
        "credentials" to credentials.map { credential ->
            jsonOf(
                "id" to credential.id,
                "type" to credential.type,
                "label" to credential.label,
                "publicKeyText" to credential.publicKeyText,
                "createdAt" to credential.createdAt,
            )
        },
        "portForwards" to portForwards.map { forward ->
            jsonOf(
                "id" to forward.id,
                "hostId" to forward.hostId,
                "type" to forward.type,
                "bindAddress" to forward.bindAddress,
                "bindPort" to forward.bindPort,
                "targetHost" to forward.targetHost,
                "targetPort" to forward.targetPort,
                "autoStart" to forward.autoStart,
            )
        },
        "scripts" to scripts.map { script ->
            jsonOf(
                "id" to script.id,
                "name" to script.name,
                "color" to script.color,
                "targetHostId" to script.targetHostId,
                "snippet" to script.snippet,
                "mode" to script.mode,
                "disconnectAfter" to script.disconnectAfter,
                "paramsJson" to script.paramsJson,
                "confirmBeforeRun" to script.confirmBeforeRun,
                "allowAutomation" to script.allowAutomation,
                "createdAt" to script.createdAt,
            )
        },
        "terminalProfiles" to terminalProfiles.map { profile ->
            jsonOf(
                "id" to profile.id,
                "name" to profile.name,
                "fontFamily" to profile.fontFamily,
                "customFontUri" to profile.customFontUri,
                "fontSizeSp" to profile.fontSizeSp,
                "lineHeightMultiplier" to profile.lineHeightMultiplier,
                "cursorStyle" to profile.cursorStyle,
                "cursorBlink" to profile.cursorBlink,
                "scrollbackLines" to profile.scrollbackLines,
            )
        },
        "colourSchemes" to colorSchemes.map { scheme ->
            jsonOf(
                "id" to scheme.id,
                "name" to scheme.name,
                "isBuiltIn" to scheme.isBuiltIn,
                "background" to scheme.background,
                "foreground" to scheme.foreground,
                "cursor" to scheme.cursor,
                "selection" to scheme.selection,
                "ansiColorsCsv" to scheme.ansiColorsCsv,
            )
        },
        "keyBarLayouts" to keyBarLayouts.map { layout ->
            jsonOf(
                "id" to layout.id,
                "name" to layout.name,
                "keysJson" to layout.keysJson,
                "rows" to layout.rows,
            )
        },
    ).toString(2)

/** [JSONObject.NULL] and not `null`, which `put` would drop the key for. */
private fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject =
    JSONObject().apply {
        pairs.forEach { (key, value) ->
            put(
                key,
                when (value) {
                    null -> JSONObject.NULL
                    is Collection<*> -> JSONArray(value)
                    else -> value
                },
            )
        }
    }

@Singleton
class ConfigExporter
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val db: ShellwaveDatabase,
) {
    suspend fun writeTo(uri: Uri) {
        withContext(Dispatchers.IO) {
            val hosts = db.hostDao().observeAll().first()
            val json =
                buildConfigExport(
                    hosts = hosts,
                    credentials = db.credentialDao().observeAll().first(),
                    portForwards = hosts.flatMap { db.portForwardDao().getForHost(it.id) },
                    scripts = db.scriptDao().observeAll().first(),
                    terminalProfiles = db.terminalProfileDao().getAll(),
                    colorSchemes = db.colorSchemeDao().getAll(),
                    keyBarLayouts = db.keyBarLayoutDao().getAll(),
                    settings = settings(),
                    exportedAt = Instant.now(),
                )
            // "wt" truncates: CreateDocument can hand back a file the user already had.
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(json.toByteArray())
            } ?: throw IOException("Could not open the chosen file for writing")
        }
    }

    /**
     * Read one setting at a time. Dumping the `SharedPreferences` map would be shorter and would
     * also carry `automation_token`, the one preference that is a credential.
     */
    private fun settings(): Map<String, Any?> =
        mapOf(
            "themeMode" to AppearancePreferences.getThemeMode(context).name,
            "dynamicColour" to AppearancePreferences.getDynamicColor(context),
            "exactSchemeColours" to AppearancePreferences.getExactSchemeColours(context),
            "bellMode" to BellPreferences.get(context).name,
            "fullWidthTerminal" to SessionLayoutPreferences.getFullWidthTerminal(context),
            "reachabilityEnabled" to ReachabilityPreferences.isEnabled(context),
            "reachabilityInterval" to ReachabilityPreferences.interval(context).name,
            "reachabilityAllowsMetered" to ReachabilityPreferences.allowsMetered(context),
            "automationEnabled" to AutomationPreferences.isEnabled(context),
            "widgetPinnedScriptIds" to WidgetPreferences.pinnedScriptIds(context).sorted(),
            "quickSettingsTileScriptId" to WidgetPreferences.qsTileScriptId(context),
        )
}
