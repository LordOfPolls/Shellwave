package io.github.lordofpolls.shellwave.feature.settings

import android.content.Context
import android.net.Uri
import androidx.glance.appwidget.updateAll
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lordofpolls.shellwave.core.crypto.CredentialType
import io.github.lordofpolls.shellwave.core.db.ShellwaveDatabase
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.prefs.AppearancePreferences
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityInterval
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityPreferences
import io.github.lordofpolls.shellwave.core.prefs.SessionLayoutPreferences
import io.github.lordofpolls.shellwave.core.prefs.ThemeMode
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import io.github.lordofpolls.shellwave.feature.glance.ScriptWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything [buildConfigExport] wrote, with the exporting device's ids still on it. Nothing reuses
 * those ids: they exist so [ConfigImporter] can repoint each reference at the row's freshly-assigned
 * one.
 */
data class ParsedConfig(
    val hosts: List<HostEntity>,
    val credentials: List<CredentialEntity>,
    val portForwards: List<PortForwardEntity>,
    val scripts: List<ScriptEntity>,
    val terminalProfiles: List<TerminalProfileEntity>,
    val colorSchemes: List<ColorSchemeEntity>,
    val keyBarLayouts: List<KeyBarLayoutEntity>,
    val settings: ImportedSettings,
)

data class ImportedSettings(
    val themeMode: ThemeMode? = null,
    val dynamicColour: Boolean? = null,
    val exactSchemeColours: Boolean? = null,
    val bellMode: BellMode? = null,
    val fullWidthTerminal: Boolean? = null,
    val reachabilityEnabled: Boolean? = null,
    val reachabilityInterval: ReachabilityInterval? = null,
    val reachabilityAllowsMetered: Boolean? = null,
    val automationWasEnabled: Boolean? = null,
    val widgetPinnedScriptIds: List<Long> = emptyList(),
    val quickSettingsTileScriptId: Long? = null,
)

data class ConfigImportSummary(
    val hosts: Int = 0,
    val credentials: Int = 0,
    val portForwards: Int = 0,
    val scripts: Int = 0,
    val terminalProfiles: Int = 0,
    val colourSchemes: Int = 0,
    val keyBarLayouts: Int = 0,
    val notes: List<String> = emptyList(),
) {
    fun lines(): List<String> {
        val counts =
            listOfNotNull(
                counted(hosts, "host"),
                counted(credentials, "credential"),
                counted(portForwards, "tunnel"),
                counted(scripts, "script"),
                counted(terminalProfiles, "terminal profile"),
                counted(colourSchemes, "colour scheme"),
                counted(keyBarLayouts, "key bar layout"),
            )
        val headline =
            if (counts.isEmpty()) "That file held nothing to import."
            else "Imported ${counts.joinToString(", ")}."
        return listOf(headline) + notes
    }

    private fun counted(count: Int, noun: String): String? =
        when (count) {
            0 -> null
            1 -> "1 $noun"
            else -> "$count ${noun}s"
        }
}

fun parseConfigImport(text: String): ParsedConfig {
    val root =
        runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("That file is not JSON.") }
    if (root.optString("format") != "shellwave-config") {
        throw IllegalArgumentException("That file is not a Shellwave configuration export.")
    }
    val version = root.optInt("version", 0)
    if (version < 1) {
        throw IllegalArgumentException("That file is not a Shellwave configuration export.")
    }
    if (version > CONFIG_EXPORT_VERSION) {
        throw IllegalArgumentException(
            "That export is in format version $version and this build reads up to " +
                "$CONFIG_EXPORT_VERSION. Update Shellwave first.",
        )
    }
    return try {
        ParsedConfig(
            hosts = root.objects("hosts").map { it.toHost() },
            credentials = root.objects("credentials").map { it.toCredential() },
            portForwards = root.objects("portForwards").map { it.toPortForward() },
            scripts = root.objects("scripts").map { it.toScript() },
            terminalProfiles = root.objects("terminalProfiles").map { it.toTerminalProfile() },
            colorSchemes = root.objects("colourSchemes").map { it.toColorScheme() },
            keyBarLayouts = root.objects("keyBarLayouts").map { it.toKeyBarLayout() },
            settings = root.optJSONObject("settings").toImportedSettings(),
        )
    } catch (e: JSONException) {
        throw IllegalArgumentException("That export is missing something Shellwave needs: ${e.message}")
    }
}

private fun JSONObject.toHost(): HostEntity =
    HostEntity(
        id = getLong("id"),
        label = stringOrNull("label"),
        hostname = getString("hostname"),
        port = getInt("port"),
        username = getString("username"),
        credentialId = getLong("credentialId"),
        lastConnectedAt = longOrNull("lastConnectedAt"),
        createdAt = getLong("createdAt"),
        resilientSession = optBoolean("resilientSession"),
        terminalProfileId = longOrNull("terminalProfileId"),
        colorSchemeId = longOrNull("colorSchemeId"),
        keyBarLayoutId = longOrNull("keyBarLayoutId"),
        proxyJumpHostId = longOrNull("proxyJumpHostId"),
        macAddress = stringOrNull("macAddress"),
    )

private fun JSONObject.toCredential(): CredentialEntity =
    CredentialEntity(
        id = getLong("id"),
        type = getString("type"),
        label = stringOrNull("label"),
        keystoreAlias = null,
        secretIv = null,
        secretCiphertext = null,
        passphraseIv = null,
        passphraseCiphertext = null,
        publicKeyText = stringOrNull("publicKeyText"),
        createdAt = getLong("createdAt"),
    )

private fun JSONObject.toPortForward(): PortForwardEntity =
    PortForwardEntity(
        id = getLong("id"),
        hostId = getLong("hostId"),
        type = getString("type"),
        bindAddress = stringOrNull("bindAddress"),
        bindPort = getInt("bindPort"),
        targetHost = stringOrNull("targetHost"),
        targetPort = intOrNull("targetPort"),
        autoStart = optBoolean("autoStart"),
    )

private fun JSONObject.toScript(): ScriptEntity =
    ScriptEntity(
        id = getLong("id"),
        name = getString("name"),
        icon = null,
        color = intOrNull("color"),
        targetHostId = longOrNull("targetHostId"),
        snippet = getString("snippet"),
        mode = getString("mode"),
        disconnectAfter = optBoolean("disconnectAfter"),
        paramsJson = getString("paramsJson"),
        confirmBeforeRun = optBoolean("confirmBeforeRun"),
        allowAutomation = optBoolean("allowAutomation"),
        createdAt = getLong("createdAt"),
    )

private fun JSONObject.toTerminalProfile(): TerminalProfileEntity =
    TerminalProfileEntity(
        id = getLong("id"),
        name = getString("name"),
        fontFamily = getString("fontFamily"),
        customFontUri = stringOrNull("customFontUri"),
        fontSizeSp = getDouble("fontSizeSp").toFloat(),
        lineHeightMultiplier = getDouble("lineHeightMultiplier").toFloat(),
        cursorStyle = getString("cursorStyle"),
        cursorBlink = optBoolean("cursorBlink"),
        scrollbackLines = getInt("scrollbackLines"),
    )

private fun JSONObject.toColorScheme(): ColorSchemeEntity =
    ColorSchemeEntity(
        id = getLong("id"),
        name = getString("name"),
        isBuiltIn = optBoolean("isBuiltIn"),
        background = getInt("background"),
        foreground = getInt("foreground"),
        cursor = getInt("cursor"),
        selection = getInt("selection"),
        ansiColorsCsv = getString("ansiColorsCsv"),
    )

private fun JSONObject.toKeyBarLayout(): KeyBarLayoutEntity =
    KeyBarLayoutEntity(
        id = getLong("id"),
        name = getString("name"),
        keysJson = getString("keysJson"),
        rows = optInt("rows", 1),
    )

private fun JSONObject?.toImportedSettings(): ImportedSettings {
    if (this == null) return ImportedSettings()
    val pinned = optJSONArray("widgetPinnedScriptIds")
    return ImportedSettings(
        themeMode = enumOrNull(stringOrNull("themeMode")),
        dynamicColour = booleanOrNull("dynamicColour"),
        exactSchemeColours = booleanOrNull("exactSchemeColours"),
        bellMode = enumOrNull(stringOrNull("bellMode")),
        fullWidthTerminal = booleanOrNull("fullWidthTerminal"),
        reachabilityEnabled = booleanOrNull("reachabilityEnabled"),
        reachabilityInterval = enumOrNull(stringOrNull("reachabilityInterval")),
        reachabilityAllowsMetered = booleanOrNull("reachabilityAllowsMetered"),
        automationWasEnabled = booleanOrNull("automationEnabled"),
        widgetPinnedScriptIds =
            (0 until (pinned?.length() ?: 0)).mapNotNull { pinned?.optLong(it) },
        quickSettingsTileScriptId = longOrNull("quickSettingsTileScriptId"),
    )
}

private fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)

private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)

private fun JSONObject.booleanOrNull(key: String): Boolean? =
    if (isNull(key)) null else getBoolean(key)

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

private data class InsertedRows(
    val summary: ConfigImportSummary,
    val scriptIds: Map<Long, Long>,
)

@Singleton
class ConfigImporter
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val db: ShellwaveDatabase,
) {
    suspend fun readFrom(uri: Uri): ConfigImportSummary =
        withContext(Dispatchers.IO) {
            val text =
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: throw IOException("Could not open the chosen file for reading")
            restore(parseConfigImport(text))
        }

    private suspend fun restore(parsed: ParsedConfig): ConfigImportSummary {
        val notes = mutableListOf<String>()
        val inserted = db.withTransaction { insertRows(parsed, notes) }
        applySettings(parsed.settings, inserted.scriptIds, notes)
        // The pinned ids just changed underneath it.
        ScriptWidget().updateAll(context)
        return inserted.summary.copy(notes = notes)
    }

    private suspend fun insertRows(parsed: ParsedConfig, notes: MutableList<String>): InsertedRows {
        val profileIds = importTerminalProfiles(parsed.terminalProfiles, db.terminalProfileDao())
        val schemeIds = importColorSchemes(parsed.colorSchemes, db.colorSchemeDao())
        val layoutIds =
            parsed.keyBarLayouts.associate { it.id to db.keyBarLayoutDao().insert(it.copy(id = 0)) }
        val credentialIds =
            parsed.credentials.associate { it.id to db.credentialDao().insert(it.copy(id = 0)) }
        if (parsed.credentials.any { it.type != CredentialType.KEYBOARD_INTERACTIVE.name }) {
            notes +=
                "Passwords and keys were not in the file, so those credentials came across empty. " +
                    "Open each host and enter its secret again before connecting."
        }

        val hostIds = mutableMapOf<Long, Long>()
        parsed.hosts.forEach { host ->
            val credentialId = credentialIds[host.credentialId]
            if (credentialId == null) {
                notes += "${host.importName()}: skipped - it names a credential the file does not contain."
                return@forEach
            }
            hostIds[host.id] =
                db.hostDao().insert(
                    host.copy(
                        id = 0,
                        credentialId = credentialId,
                        terminalProfileId = host.terminalProfileId?.let { profileIds[it] },
                        colorSchemeId = host.colorSchemeId?.let { schemeIds[it] },
                        keyBarLayoutId = host.keyBarLayoutId?.let { layoutIds[it] },
                        // Repointed in the second pass below.
                        proxyJumpHostId = null,
                    ),
                )
        }

        parsed.hosts.forEach { host ->
            val newId = hostIds[host.id] ?: return@forEach
            val jumpTarget = host.proxyJumpHostId ?: return@forEach
            val jumpId = hostIds[jumpTarget]
            if (jumpId == null) {
                notes += "${host.importName()}: its proxy jump host was not imported, so it will connect directly."
                return@forEach
            }
            db.hostDao().getById(newId)?.let {
                db.hostDao().update(it.copy(proxyJumpHostId = jumpId))
            }
        }

        val forwards =
            parsed.portForwards.count { forward ->
                val hostId = hostIds[forward.hostId] ?: return@count false
                db.portForwardDao().insert(forward.copy(id = 0, hostId = hostId))
                true
            }

        val scriptIds =
            parsed.scripts.associate { script ->
                script.id to
                    db.scriptDao().insert(
                        script.copy(
                            id = 0,
                            targetHostId = script.targetHostId?.let { hostIds[it] },
                        ),
                    )
            }

        return InsertedRows(
            summary =
                ConfigImportSummary(
                    hosts = hostIds.size,
                    credentials = credentialIds.size,
                    portForwards = forwards,
                    scripts = scriptIds.size,
                    terminalProfiles = profileIds.size,
                    colourSchemes = schemeIds.size,
                    keyBarLayouts = layoutIds.size,
                ),
            scriptIds = scriptIds,
        )
    }

    private fun applySettings(
        settings: ImportedSettings,
        scriptIds: Map<Long, Long>,
        notes: MutableList<String>,
    ) {
        settings.themeMode?.let { AppearancePreferences.setThemeMode(context, it) }
        settings.dynamicColour?.let { AppearancePreferences.setDynamicColor(context, it) }
        settings.exactSchemeColours?.let { AppearancePreferences.setExactSchemeColours(context, it) }
        settings.bellMode?.let { BellPreferences.set(context, it) }
        settings.fullWidthTerminal?.let {
            SessionLayoutPreferences.setFullWidthTerminal(context, it)
        }
        settings.reachabilityEnabled?.let { ReachabilityPreferences.setEnabled(context, it) }
        settings.reachabilityInterval?.let { ReachabilityPreferences.setInterval(context, it) }
        settings.reachabilityAllowsMetered?.let {
            ReachabilityPreferences.setAllowsMetered(context, it)
        }

        settings.widgetPinnedScriptIds
            .mapNotNull { scriptIds[it] }
            .forEach { WidgetPreferences.setPinned(context, it, pinned = true) }
        settings.quickSettingsTileScriptId
            ?.let { scriptIds[it] }
            ?.let { WidgetPreferences.setQsTileScriptId(context, it) }

        if (settings.automationWasEnabled == true) {
            notes +=
                "\"Let other apps run scripts\" was on when this was exported and has been left " +
                    "off - turn it on yourself, then copy the new token into whatever calls it."
        }
    }
}

private fun HostEntity.importName(): String = label ?: "$username@$hostname"

internal suspend fun importTerminalProfiles(
    profiles: List<TerminalProfileEntity>,
    dao: TerminalProfileDao,
): Map<Long, Long> = profiles.associate { it.id to dao.insert(it.copy(id = 0)) }

internal suspend fun importColorSchemes(
    schemes: List<ColorSchemeEntity>,
    dao: ColorSchemeDao,
): Map<Long, Long> = schemes.associate { it.id to dao.insert(it.copy(id = 0)) }
