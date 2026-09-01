package io.github.lordofpolls.shellwave.feature.scripts

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.dao.ScriptDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.prefs.AutomationPreferences
import io.github.lordofpolls.shellwave.core.util.extractParamNames
import io.github.lordofpolls.shellwave.ui.design.BackTopBar
import io.github.lordofpolls.shellwave.ui.design.MachineText
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

    // Keyed on existing?.id, never plain remember - see AddEditHostScreen. `prefill` joins the key
    // because it can move without existing?.id moving: picking a second template hands this screen
    // different values while existing?.id stays null, and the first template's text would latch.
    var name by rememberFormState(existing?.id to prefill) { initial?.name.orEmpty() }
    var color by rememberFormState(existing?.id to prefill) { initial?.color ?: ScriptColor.DEFAULT }
    var targetHostId by rememberFormState(existing?.id to prefill) { initial?.targetHostId }
    var snippet by rememberFormState(existing?.id to prefill) { initial?.snippet.orEmpty() }
    var mode by rememberFormState(existing?.id to prefill) {
        runCatching { ScriptMode.valueOf(initial?.mode ?: "") }.getOrDefault(ScriptMode.ATTACH)
    }
    var disconnectAfter by rememberFormState(existing?.id to prefill) { initial?.disconnectAfter ?: false }
    var confirmBeforeRun by rememberFormState(existing?.id to prefill) { initial?.confirmBeforeRun ?: false }
    var allowAutomation by rememberFormState(existing?.id to prefill) { initial?.allowAutomation ?: false }
    var error by remember { mutableStateOf<String?>(null) }

    val paramDefs = remember(existing?.id, prefill) {
        mutableStateMapOf<String, ScriptParam>().apply {
            decodeParams(initial?.paramsJson.orEmpty()).forEach {
                put(
                    it.name,
                    it
                )
            }
        }
    }
    val activeParamNames = extractParamNames(snippet)

    // A null targetHostId is a complete answer, "ask which host each run", so the only things left
    // to require are the two a script cannot exist without.
    fun canSave(): Boolean = name.isNotBlank() && snippet.isNotBlank()

    fun save() {
        val params = activeParamNames.map { paramDefs[it] ?: ScriptParam(it) }
        scope.launch {
            try {
                val entity =
                    ScriptEntity(
                        id = existing?.id ?: 0,
                        name = name,
                        icon = existing?.icon,
                        color = color,
                        targetHostId = if (mode == ScriptMode.SEND_TO_CURRENT) null else targetHostId,
                        snippet = snippet,
                        mode = mode.name,
                        disconnectAfter = disconnectAfter,
                        paramsJson = encodeParams(params),
                        confirmBeforeRun = confirmBeforeRun,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        allowAutomation = allowAutomation,
                    )
                if (existing == null) scriptDao.insert(entity) else scriptDao.update(entity)
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Failed to save script"
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(
            title = if (existing == null) "Add script" else "Edit script",
            onBack = onDone,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == candidate, onClick = { mode = candidate })
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
                Text("Target host", style = MaterialTheme.typography.titleSmall)
                // "Ask each run" answers the same question the hosts do, so one radio group makes "no
                // fixed host" read as a choice rather than the absence of one. First in the list.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = targetHostId == null, onClick = { targetHostId = null })
                    Column {
                        Text("Ask each run")
                        Text(
                            "Reusable - pick a host when you run it. Not available to widgets, tiles or shortcuts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                hosts.forEach { host ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = targetHostId == host.id,
                            onClick = { targetHostId = host.id })
                        // A user-chosen label is human text; a bare hostname is a machine assertion.
                        val hostLabel = host.label
                        if (hostLabel != null) Text(hostLabel) else MachineText(host.hostname)
                    }
                }
            }

            OutlinedTextField(
                value = snippet,
                onValueChange = { snippet = it },
                label = { Text("Snippet (use {{name}} for parameters)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )

            if (activeParamNames.isNotEmpty()) {
                Text("Parameters", style = MaterialTheme.typography.titleSmall)
                activeParamNames.forEach { paramName ->
                    ParamEditorRow(
                        param = paramDefs[paramName] ?: ScriptParam(paramName),
                        onChange = { updated -> paramDefs[paramName] = updated },
                    )
                }
            }

            if (mode == ScriptMode.ATTACH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = disconnectAfter, onCheckedChange = { disconnectAfter = it })
                    Text("Close the session once the command finishes")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = confirmBeforeRun, onCheckedChange = { confirmBeforeRun = it })
                Text("Confirm before running")
            }

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
                TextButton(onClick = onDone) { Text("Cancel") }
                Button(onClick = ::save, enabled = canSave()) { Text("Save") }
            }
        }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allowed, onCheckedChange = onAllowedChange)
            Column {
                Text("Let other apps run this script")
                Text(
                    "Lets Tasker trigger this script without opening Shellwave, if automation is on in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = param.type == candidate,
                        onClick = { onChange(param.copy(type = candidate)) })
                    Text(candidate.name.lowercase())
                }
            }
        }
        if (param.type == ParamType.CHOICE) {
            OutlinedTextField(
                value = param.choices.joinToString(", "),
                onValueChange = {
                    onChange(
                        param.copy(
                            choices = it.split(",").map(String::trim).filter(String::isNotEmpty)
                        )
                    )
                },
                label = { Text("Choices (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** TalkBack names for [ScriptColor.PRESETS], positionally: made-up accent shades have no canonical name. */
private val COLOR_PRESET_NAMES: List<String> =
    listOf("Green", "Teal", "Red", "Amber", "Purple", "Blue")

@Composable
private fun ColorSwatch(argb: Int, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(argb))
                // selectable over clickable: exactly one colour is selected, so Role.RadioButton
                // plus `selected` is the right semantics. A contentDescription would announce the
                // colour but never whether it is the chosen one.
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .semantics { contentDescription = description }
                .then(if (selected) Modifier.padding(2.dp) else Modifier),
    )
}

/** Matches the enum names ScriptsScreen renders, so the list and this picker say the same thing. */
private fun ScriptMode.label(): String =
    when (this) {
        ScriptMode.ATTACH -> "Attach"
        ScriptMode.CAPTURE -> "Capture"
        ScriptMode.SEND_TO_CURRENT -> "Send to current session"
    }

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
