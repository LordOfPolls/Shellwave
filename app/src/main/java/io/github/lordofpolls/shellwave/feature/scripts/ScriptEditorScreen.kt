package io.github.lordofpolls.shellwave.feature.scripts

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.dao.ScriptDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.prefs.AutomationPreferences
import io.github.lordofpolls.shellwave.core.util.extractParamNames
import io.github.lordofpolls.shellwave.feature.settings.SettingsSwitch
import io.github.lordofpolls.shellwave.ui.design.BackTopBar
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import io.github.lordofpolls.shellwave.ui.design.ColorSwatch
import io.github.lordofpolls.shellwave.ui.design.LocalReadableInset
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.PickerRow
import io.github.lordofpolls.shellwave.ui.design.RadioRow
import io.github.lordofpolls.shellwave.ui.design.rememberFormState
import kotlinx.coroutines.launch

/**
 * Add or edit a script. Same form conventions as AddEditHostScreen.
 *
 * The parameter editor is derived live from [snippet]'s placeholders ([extractParamNames]) instead
 * of kept as its own list, and only placeholders still in the snippet are saved, so
 * `ScriptEntity.paramsJson` cannot describe a parameter the snippet does not reference.
 *
 * [prefill] carries a starter template's values for a script that does not exist yet. It seeds the
 * fields as [existing] would but stays a separate parameter, because `save()` picks insert versus
 * update on `existing == null`: a template arriving as [existing] would call `scriptDao.update` on
 * a row with `id = 0`, matching nothing and discarding the script.
 */
@Composable
fun ScriptEditorScreen(
    existing: ScriptEntity?,
    hosts: List<HostEntity>,
    scriptDao: ScriptDao,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    prefill: ScriptEntity? = null,
) {
    val scope = rememberCoroutineScope()

    val initial = existing ?: prefill

    // Keyed on existing?.id, never plain remember - see AddEditHostScreen. The template joins the key
    // because it can move without existing?.id moving: picking a second template hands this screen
    // different values while existing?.id stays null, and the first template's text would latch. By
    // name, so the key can be saved across recreation; the entity itself can't.
    val formKey = existing?.id to prefill?.name
    val pristine = remember(formKey) { scriptFormBaseline(existing, initial) }
    var name by rememberFormState(formKey) { pristine.name }
    var color by rememberFormState(formKey) { initial?.color ?: ScriptColor.DEFAULT }
    var targetHostId by rememberFormState(formKey) { initial?.targetHostId }
    var snippet by rememberFormState(formKey) { pristine.snippet }
    var mode by rememberFormState(formKey) { ScriptMode.valueOf(pristine.mode) }
    var disconnectAfter by rememberFormState(formKey) { pristine.disconnectAfter }
    var confirmBeforeRun by rememberFormState(formKey) { pristine.confirmBeforeRun }
    var allowAutomation by rememberFormState(formKey) { pristine.allowAutomation }
    var paramsJson by rememberFormState(formKey) { initial?.paramsJson.orEmpty() }
    var error by remember { mutableStateOf<String?>(null) }

    val paramDefs = decodeParams(paramsJson).associateBy { it.name }
    val activeParamNames = extractParamNames(snippet)

    // A null targetHostId is a complete answer, "ask which host each run", so the only things left
    // to require are the two a script cannot exist without.
    fun canSave(): Boolean = name.isNotBlank() && snippet.isNotBlank()

    fun formEntity() =
        scriptFormEntity(
            existing, name, color, targetHostId, snippet, mode, disconnectAfter, paramsJson,
            confirmBeforeRun, allowAutomation,
        )

    val dirty = formEntity() != pristine
    var confirmDiscard by remember { mutableStateOf(false) }
    fun leave() {
        if (dirty) confirmDiscard = true else onDone()
    }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    fun save() {
        scope.launch {
            try {
                val entity = formEntity().copy(createdAt = existing?.createdAt ?: System.currentTimeMillis())
                if (existing == null) scriptDao.insert(entity) else scriptDao.update(entity)
                onDone()
            } catch (e: Exception) {
                // The likely cause is the target host being deleted while this form was open.
                error = "Couldn't save the script. Check the target host still exists, then save again." +
                        e.message?.let { " ($it)" }.orEmpty()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(
            title = if (existing == null) "Add script" else "Edit script",
            onBack = ::leave,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalReadableInset.current)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Text("Colour", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScriptColor.PRESETS.forEachIndexed { index, candidate ->
                    ColorSwatch(
                        argb = candidate,
                        description = COLOR_PRESET_NAMES.getOrElse(index) { "Colour ${index + 1}" },
                        selected = candidate == color,
                        onClick = { color = candidate },
                    )
                }
            }

            Text("Mode", style = MaterialTheme.typography.titleSmall)
            ScriptMode.entries.forEach { candidate ->
                RadioRow(selected = mode == candidate, onClick = { mode = candidate }) {
                    Column {
                        Text(candidate.label())
                        Text(
                            candidate.description(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (mode != ScriptMode.SEND_TO_CURRENT) {
                // "Ask each run" answers the same question the hosts do, so one menu makes "no fixed
                // host" read as a choice rather than the absence of one. First in the list.
                val askEachRun: @Composable () -> Unit = {
                    Column {
                        Text("Ask each run")
                        Text(
                            "Reusable - pick a host when you run it. Not available to widgets, tiles or shortcuts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val target = hosts.firstOrNull { it.id == targetHostId }
                PickerRow(
                    title = "Target host",
                    value = { if (target == null) askEachRun() else HostLines(target) },
                ) { dismiss ->
                    DropdownMenuItem(
                        text = askEachRun,
                        onClick = { targetHostId = null; dismiss() },
                    )
                    hosts.forEach { host ->
                        DropdownMenuItem(
                            text = { HostLines(host) },
                            onClick = { targetHostId = host.id; dismiss() },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = snippet,
                onValueChange = { snippet = it },
                label = { Text("Snippet (use {{name}} for parameters)") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = ChromeMonoFontFamily),
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )

            if (activeParamNames.isNotEmpty()) {
                Text("Parameters", style = MaterialTheme.typography.titleSmall)
                activeParamNames.forEach { paramName ->
                    ParamEditorRow(
                        param = paramDefs[paramName] ?: ScriptParam(paramName),
                        onChange = { updated ->
                            paramsJson = encodeParams((paramDefs + (paramName to updated)).values.toList())
                        },
                    )
                }
            }

            if (mode == ScriptMode.ATTACH) {
                SettingsSwitch(
                    title = "Close the session once the command finishes",
                    description = null,
                    checked = disconnectAfter,
                    onCheckedChange = { disconnectAfter = it },
                )
            }

            SettingsSwitch(
                title = "Confirm before running",
                description = null,
                checked = confirmBeforeRun,
                onCheckedChange = { confirmBeforeRun = it },
            )

            // The integration addresses a script by numeric id, and an unsaved script has no id to
            // paste into a task. Offering the toggle first would offer a capability nobody can wire up
            // yet.
            if (existing != null) {
                AutomationOptIn(
                    scriptId = existing.id,
                    allowed = allowAutomation,
                    onAllowedChange = { allowAutomation = it })
            }

            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = ::leave) { Text("Cancel") }
                Button(onClick = ::save, enabled = canSave()) { Text("Save") }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this script haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDone() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

/**
 * The per-script half of the automation gate, plus the id another app has to name.
 *
 * The id shows only when the toggle is on. It is not a secret - ids are small integers - but
 * showing it always would put a "copy this into Tasker" affordance on every script in the app, most
 * of which are not reachable that way, and an affordance that does nothing is how a user concludes
 * the toggle was optional.
 *
 * The reminder about the app-wide switch states a fact; it is no shortcut into Settings. Arming a
 * script and opening the app-wide door are two decisions.
 */
@Composable
private fun AutomationOptIn(scriptId: Long, allowed: Boolean, onAllowedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // A reminder rather than a control; Settings is unreachable from a half-edited form anyway.
    val appWideEnabled = remember { AutomationPreferences.isEnabled(context) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSwitch(
            title = "Let other apps run this script",
            description = "Lets Tasker trigger this script without opening Shellwave, if automation is on in Settings.",
            checked = allowed,
            onCheckedChange = onAllowedChange,
        )
        if (allowed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Script id", style = MaterialTheme.typography.bodyMedium)
                MachineText(scriptId.toString())
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText(
                                    "script id",
                                    scriptId.toString()
                                )
                            )
                        )
                    }
                }) {
                    Text("Copy")
                }
            }
            if (!appWideEnabled) {
                Text(
                    "Automation is currently off app-wide, so nothing can trigger this yet. Turn on \"Let other apps run scripts\" in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ParamEditorRow(param: ScriptParam, onChange: (ScriptParam) -> Unit) {
    // Re-joining the parsed list on every keystroke would swallow a trailing comma.
    var rawChoices by rememberSaveable(param.name) { mutableStateOf(param.choices.joinToString(", ")) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("{{${param.name}}}", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = param.label,
            onValueChange = { onChange(param.copy(label = it)) },
            label = { Text("Prompt label") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ParamType.entries.forEach { candidate ->
                RadioRow(
                    selected = param.type == candidate,
                    onClick = { onChange(param.copy(type = candidate)) },
                ) {
                    Text(candidate.name.lowercase())
                }
            }
        }
        if (param.type == ParamType.CHOICE) {
            OutlinedTextField(
                value = rawChoices,
                onValueChange = {
                    rawChoices = it
                    onChange(param.copy(choices = parseChoices(it)))
                },
                label = { Text("Choices (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun scriptFormEntity(
    existing: ScriptEntity?,
    name: String,
    color: Int,
    targetHostId: Long?,
    snippet: String,
    mode: ScriptMode,
    disconnectAfter: Boolean,
    paramsJson: String,
    confirmBeforeRun: Boolean,
    allowAutomation: Boolean,
): ScriptEntity {
    val defs = decodeParams(paramsJson).associateBy { it.name }
    return ScriptEntity(
        id = existing?.id ?: 0,
        name = name,
        icon = existing?.icon,
        color = color,
        targetHostId = if (mode == ScriptMode.SEND_TO_CURRENT) null else targetHostId,
        snippet = snippet,
        mode = mode.name,
        disconnectAfter = disconnectAfter,
        paramsJson = encodeParams(extractParamNames(snippet).map { defs[it] ?: ScriptParam(it) }),
        confirmBeforeRun = confirmBeforeRun,
        createdAt = existing?.createdAt ?: 0,
        allowAutomation = allowAutomation,
    )
}

/**
 * The unsaved-changes baseline, built from the row or template and never from the live fields:
 * after recreation those already hold the restored edits, which would compare equal to themselves.
 */
internal fun scriptFormBaseline(existing: ScriptEntity?, initial: ScriptEntity?): ScriptEntity =
    scriptFormEntity(
        existing = existing,
        name = initial?.name.orEmpty(),
        color = initial?.color ?: ScriptColor.DEFAULT,
        targetHostId = initial?.targetHostId,
        snippet = initial?.snippet.orEmpty(),
        mode = runCatching { ScriptMode.valueOf(initial?.mode ?: "") }.getOrDefault(ScriptMode.ATTACH),
        disconnectAfter = initial?.disconnectAfter ?: false,
        paramsJson = initial?.paramsJson.orEmpty(),
        confirmBeforeRun = initial?.confirmBeforeRun ?: false,
        allowAutomation = initial?.allowAutomation ?: false,
    )

/** TalkBack names for [ScriptColor.PRESETS], positionally: made-up accent shades have no canonical name. */
private val COLOR_PRESET_NAMES: List<String> =
    listOf("Green", "Teal", "Red", "Amber", "Purple", "Blue")

/**
 * The names alone do not distinguish them: "send to current" was asked for as a missing feature
 * while it already existed, which is a label failing rather than a feature.
 */
private fun ScriptMode.description(): String =
    when (this) {
        ScriptMode.ATTACH -> "Connect to the host, run the snippet, and stay in the session."
        ScriptMode.CAPTURE -> "Run it without opening a session and show the output."
        ScriptMode.SEND_TO_CURRENT -> "Type it into whichever session is already open. Needs no host."
    }
