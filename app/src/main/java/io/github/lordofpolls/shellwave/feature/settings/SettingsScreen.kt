package io.github.lordofpolls.shellwave.feature.settings

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.billing.SupporterState
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityInterval
import io.github.lordofpolls.shellwave.core.prefs.ThemeMode
import io.github.lordofpolls.shellwave.service.ACTION_RUN_SCRIPT
import io.github.lordofpolls.shellwave.service.EXTRA_SCRIPT_ID
import io.github.lordofpolls.shellwave.service.EXTRA_TOKEN
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.ui.design.AdvancedSection
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The Settings destination, as M3 preference groups.
 *
 * Sections and not cards: a `titleSmall` header already groups its items, and a `Card` would spend
 * height drawing a container around that.
 *
 * Disclosure is per item. Terminal stays visible with each of its two field editors behind its own
 * [AdvancedSection] - font size, cursor style and sixteen ANSI hex values are real settings nobody
 * opens twice - because one "Advanced" group holding both would hide the Terminal heading. There is
 * no Security group: host key trust is per host and biometric gating is per credential, so it would
 * be empty.
 *
 * [profile]/[scheme] and the `LaunchedEffect`s loading them sit in this composable's own scope,
 * since a collapsed [AdvancedSection] drops its content from composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    terminalProfileDao: TerminalProfileDao,
    colorSchemeDao: ColorSchemeDao,
    dynamicColorEnabled: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    exactSchemeColours: Boolean,
    onExactSchemeColoursChange: (Boolean) -> Unit,
    reachabilityEnabled: Boolean,
    onReachabilityEnabledChange: (Boolean) -> Unit,
    reachabilityInterval: ReachabilityInterval,
    onReachabilityIntervalChange: (ReachabilityInterval) -> Unit,
    reachabilityMetered: Boolean,
    onReachabilityMeteredChange: (Boolean) -> Unit,
    automationEnabled: Boolean,
    onAutomationEnabledChange: (Boolean) -> Unit,
    /** Null until the switch has been turned on for the first time, which is what mints one. */
    automationToken: String?,
    onRegenerateAutomationToken: () -> Unit,
    onOpenKeyBarLayouts: () -> Unit,
    onExportConfig: suspend (Uri) -> Unit,
    onImportConfig: suspend (Uri) -> ConfigImportSummary,
    onOpenLicenses: () -> Unit,
    supporterState: SupporterState,
    onBecomeSupporter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf(DEFAULT_TERMINAL_PROFILE) }
    LaunchedEffect(Unit) {
        terminalProfileDao.getDefault()?.let { profile = it }
    }

    // Insert on first edit, update the same row thereafter. Applied optimistically to local state.
    fun save(updated: TerminalProfileEntity) {
        profile = updated
        scope.launch {
            if (updated.id == 0L) {
                profile = updated.copy(id = terminalProfileDao.insert(updated))
            } else {
                terminalProfileDao.update(updated)
            }
        }
    }

    // Writing is all this screen does: MainActivity's observeDefault() collector calls
    // SessionManager.applyDefaultColorScheme so an edit reaches an already-open session.
    var scheme by remember { mutableStateOf(DEFAULT_COLOR_SCHEME) }
    LaunchedEffect(Unit) {
        colorSchemeDao.getDefault()?.let { scheme = it }
    }

    fun saveScheme(updated: ColorSchemeEntity) {
        scheme = updated
        scope.launch {
            if (updated.id == 0L) {
                scheme = updated.copy(id = colorSchemeDao.insert(updated))
            } else {
                colorSchemeDao.update(updated)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        // MainActivity's Scaffold already applied the system bar insets without consuming them.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSectionHeader("Appearance", first = true)

            Text("Theme", style = MaterialTheme.typography.labelLarge)
            ThemeMode.entries.forEach { mode ->
                Row(
                    // The whole row is the radio. With `onClick` on the RadioButton and the name in
                    // a sibling Text, TalkBack sees a radio button with no name and a label
                    // attached to nothing. `selectable` with `Role.RadioButton` merges them into
                    // one named node. minimumInteractiveComponentSize is needed because the row
                    // became the control: RadioButton applies it to itself, `Modifier.selectable`
                    // does not, and without it the target shrinks from 48dp to 32dp.
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .selectable(
                                selected = themeMode == mode,
                                role = Role.RadioButton,
                                onClick = { onThemeModeChange(mode) },
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = themeMode == mode, onClick = null)
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }

            SettingsSwitch(
                title = "Dynamic colour (Material You)",
                description = "Take the app's accent from your wallpaper.",
                checked = dynamicColorEnabled,
                onCheckedChange = onDynamicColorChange,
            )

            SettingsSwitch(
                title = "Exact scheme colours",
                // Plain language over CAM16/HCT: the mechanism is documented at SchemeHarmonizer,
                // and a settings row has to say what changes.
                description = "Show colour schemes exactly as authored, without nudging them toward the app's accent.",
                checked = exactSchemeColours,
                onCheckedChange = onExactSchemeColoursChange,
            )

            SettingsSectionHeader("Hosts")

            SettingsSwitch(
                title = "Show which hosts are reachable",
                // This app makes no traffic the user did not ask for, so the one feature that
                // changes that says so in the row that turns it on.
                description = "Probes each saved host's SSH port and marks it UP or DOWN. No credentials used. Stops when you leave the app.",
                checked = reachabilityEnabled,
                onCheckedChange = onReachabilityEnabledChange,
            )

            if (reachabilityEnabled) {
                Text("How often", style = MaterialTheme.typography.labelLarge)
                ReachabilityInterval.entries.forEach { candidate ->
                    Row(
                        // Same whole-row-is-the-radio treatment as the theme group above.
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .selectable(
                                    selected = reachabilityInterval == candidate,
                                    role = Role.RadioButton,
                                    onClick = { onReachabilityIntervalChange(candidate) },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reachabilityInterval == candidate, onClick = null)
                        Text(candidate.label)
                    }
                }

                SettingsSwitch(
                    title = "Allow on mobile data",
                    description = "Off by default, so probing only happens on unmetered networks such as Wi-Fi.",
                    checked = reachabilityMetered,
                    onCheckedChange = onReachabilityMeteredChange,
                )

                Text(
                    "Hosts behind a jump host aren't probed - a direct connection to them would fail - so they show \"—\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSectionHeader("Automation")

            AutomationSettings(
                enabled = automationEnabled,
                onEnabledChange = onAutomationEnabledChange,
                token = automationToken,
                onRegenerate = onRegenerateAutomationToken,
            )

            SettingsSectionHeader("Terminal")

            AdvancedSection(title = "Terminal profile") {
                TerminalProfileFields(profile = profile, onChange = ::save)
            }
            AdvancedSection(title = "Colour scheme") {
                ColorSchemeFields(scheme = scheme, onChange = ::saveScheme)
            }
            Text(
                "Key bar layouts (custom keys, macros, one or two rows) can be assigned per host.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenKeyBarLayouts) { Text("Manage key bar layouts") }

            if (supporterState != SupporterState.Unavailable) {
                SettingsSectionHeader("Support")
                SupporterRow(state = supporterState, onBecomeSupporter = onBecomeSupporter)
            }

            SettingsSectionHeader("Backup")

            ConfigExportRow(onExport = onExportConfig)
            ConfigImportRow(onImport = onImportConfig)

            SettingsSectionHeader("About")

            TextButton(onClick = onOpenLicenses) { Text("Licences") }

            Spacer(modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

/** A section is a plain `titleSmall` header above its items. */
@Composable
private fun SettingsSectionHeader(title: String, first: Boolean = false) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = if (first) 8.dp else 24.dp, bottom = 4.dp),
    )
}

/**
 * The app-wide automation gate: the switch, the token, and the two lines a user needs to wire up a
 * Tasker task without leaving the app to look up an action name.
 *
 * The description says what it lets other apps do. Every other switch here changes how Shellwave
 * looks or behaves; this one hands a capability to software the user has not audited, and the row
 * that turns it on is the only place that fact is guaranteed to be read.
 *
 * Regeneration sits behind a confirmation because it silently breaks every task holding the old
 * token, and the breakage shows up later as an automation that quietly stopped working.
 */
@Composable
private fun AutomationSettings(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    token: String?,
    onRegenerate: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var confirmingRegenerate by remember { mutableStateOf(false) }

    SettingsSwitch(
        title = "Let other apps run scripts",
        description = "Lets Tasker run a script over SSH with no confirmation. Each script must opt in, and the request needs the token below.",
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )

    if (enabled && token != null) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Token", style = MaterialTheme.typography.labelLarge)
            // A substituted lookalike glyph here would produce a silent, baffling failure in
            // another app.
            MachineText(token, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText(
                                    "Shellwave automation token",
                                    token
                                )
                            )
                        )
                    }
                }) {
                    Text("Copy token")
                }
                TextButton(onClick = { confirmingRegenerate = true }) { Text("Generate a new one") }
            }
            Text(
                "In Tasker: Send Intent, Target Activity, action $ACTION_RUN_SCRIPT, with extras " +
                        "$EXTRA_SCRIPT_ID (the script's id, shown when you edit it) and $EXTRA_TOKEN (the token above).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmingRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmingRegenerate = false },
            title = { Text("Generate a new token?") },
            text = { Text("The old token stops working immediately - update any tasks using it.") },
            confirmButton = {
                TextButton(onClick = {
                    onRegenerate()
                    confirmingRegenerate = false
                }) {
                    Text("Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmingRegenerate = false
                }) { Text("Cancel") }
            },
        )
    }
}

/**
 * A one-time, no-functionality purchase, so the copy says up front that it unlocks nothing.
 *
 * Callers skip this entirely for [SupporterState.Unavailable] - an F-Droid build or a Play Console
 * listing with no matching product - so there's nothing to render for that case here.
 */
@Composable
private fun SupporterRow(state: SupporterState, onBecomeSupporter: () -> Unit) {
    when (state) {
        is SupporterState.Loading, is SupporterState.Unavailable -> Unit

        is SupporterState.Purchasable -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "A one-time purchase that unlocks nothing - just a way to say thanks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBecomeSupporter) { Text("Become a supporter · ${state.priceLabel}") }
            }
        }

        is SupporterState.Supporter -> {
            Text("You're a supporter - thank you!", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** `CreateDocument` rather than a fixed path: no storage permission, and the user picks where it lands. */
@Composable
private fun ConfigExportRow(onExport: suspend (Uri) -> Unit) {
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            outcome = null
            scope.launch {
                outcome =
                    runCatching { onExport(uri) }
                        .fold(
                            { "Configuration exported." },
                            { it.message ?: "Could not write the export." },
                        )
            }
        }

    Text(
        "Writes your hosts, tunnels, scripts and settings to a JSON file. Passwords, private keys " +
                "and passphrases are not included - they stay sealed in this device's keystore.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { launcher.launch("shellwave-config-${LocalDate.now()}.json") }) {
        Text("Export configuration")
    }
    outcome?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * `OpenDocument`, plus a confirmation the export has no need of: picking a file to read looks
 * harmless, while what follows writes rows and replaces every app-wide setting. The dialog is where
 * that gap gets closed, and it says what an export cannot carry before the picker opens rather than
 * after the import lands.
 *
 * The summary is a list of lines and not one string, because a line per skipped host is the useful
 * shape - see [ConfigImportSummary.lines].
 */
@Composable
private fun ConfigImportRow(onImport: suspend (Uri) -> ConfigImportSummary) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<List<String>>(emptyList()) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            outcome = emptyList()
            scope.launch {
                outcome =
                    runCatching { onImport(uri) }
                        .fold(
                            { it.lines() },
                            { listOf(it.message ?: "Could not read that file.") },
                        )
            }
        }

    Text(
        "Reads a file written by Export. Hosts, tunnels, scripts and layouts are added to what you " +
                "already have; app-wide settings are replaced. Nothing is deleted.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { confirming = true }) {
        Text("Import configuration")
    }
    outcome.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Import a configuration?") },
            text = {
                Text(
                    "Everything in the file is added alongside what you already have, so hosts and " +
                            "scripts you kept will appear twice if the file also holds them. Your " +
                            "app-wide settings are replaced by the file's. Passwords and keys are not " +
                            "in an export, so imported hosts need their secret entered again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    // Any type: a document provider can hand back a .json as text/plain or
                    // application/octet-stream, and refusing those means the file is unpickable.
                    launcher.launch(arrayOf("*/*"))
                }) {
                    Text("Choose file")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/** The description is what keeps a row like "Exact scheme colours" from being author-only jargon. */
@Composable
private fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        // Same row-is-the-control treatment as the theme radios. `Role.Switch` makes TalkBack
        // announce it as a switch instead of a generic toggle.
        modifier =
            Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
