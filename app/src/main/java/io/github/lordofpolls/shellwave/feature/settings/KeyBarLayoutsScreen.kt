package io.github.lordofpolls.shellwave.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.dao.KeyBarLayoutDao
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import io.github.lordofpolls.shellwave.terminal.CURSOR_CLUSTER_LABEL
import io.github.lordofpolls.shellwave.terminal.DEFAULT_KEY_BAR_KEYS
import io.github.lordofpolls.shellwave.terminal.KeyBarKey
import io.github.lordofpolls.shellwave.terminal.KeyBarKeyType
import io.github.lordofpolls.shellwave.terminal.SPECIAL_KEY_CHOICES
import io.github.lordofpolls.shellwave.terminal.decodeKeyBarKeys
import io.github.lordofpolls.shellwave.terminal.encodeKeyBarKeys
import io.github.lordofpolls.shellwave.ui.design.BackTopBar
import io.github.lordofpolls.shellwave.ui.design.RadioRow
import kotlinx.coroutines.launch

/**
 * Each layout is edited in place below its row rather than in a second pushed screen. Per-host
 * assignment lives in AddEditHostScreen; this screen manages only the shared, named set.
 */
@Composable
fun KeyBarLayoutsScreen(
    keyBarLayoutDao: KeyBarLayoutDao,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layouts by keyBarLayoutDao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var expandedId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = "Key bar layouts", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "A host with no layout assigned uses the default bar (Esc, Tab, Home, End, arrows).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = {
                scope.launch {
                    val id = keyBarLayoutDao.insert(
                        KeyBarLayoutEntity(
                            name = "New layout",
                            keysJson = encodeKeyBarKeys(DEFAULT_KEY_BAR_KEYS)
                        )
                    )
                    expandedId = id
                }
            }) { Text("+ New layout") }

            layouts.forEach { layout ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                layout.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                expandedId = if (expandedId == layout.id) null else layout.id
                            }) {
                                Text(if (expandedId == layout.id) "Close" else "Edit")
                            }
                            TextButton(onClick = { scope.launch { keyBarLayoutDao.delete(layout) } }) {
                                Text(
                                    "Delete"
                                )
                            }
                        }
                        if (expandedId == layout.id) {
                            KeyBarLayoutEditor(
                                layout = layout,
                                onChange = { updated -> scope.launch { keyBarLayoutDao.update(updated) } })
                        }
                    }
                }
            }
        }
    }
}

/**
 * One layout's name and ordered key list, with move-up/move-down/remove instead of a drag-and-drop
 * library, plus an "add key" picker for a special keycode or a macro.
 *
 * The two-row control is a Switch instead of a segmented "1 / 2" because the choice is binary and
 * stock M3 already carries binary state to TalkBack correctly - there is no third option a wider
 * control would be making room for.
 *
 * Every `remember` here is keyed on `layout.id`, never left unkeyed: this editor is fed from a Room
 * `Flow` whose first emission can arrive after the first composition, and an unkeyed `remember`
 * would latch that first, possibly absent, value forever. That defect has shipped twice in this
 * codebase.
 */
@Composable
private fun KeyBarLayoutEditor(layout: KeyBarLayoutEntity, onChange: (KeyBarLayoutEntity) -> Unit) {
    var name by remember(layout.id) { mutableStateOf(layout.name) }
    var keys by remember(layout.id) { mutableStateOf(decodeKeyBarKeys(layout.keysJson)) }
    var rows by remember(layout.id) { mutableStateOf(layout.rows) }
    var addKeyOpen by remember { mutableStateOf(false) }

    fun persist(newName: String = name, newKeys: List<KeyBarKey> = keys, newRows: Int = rows) {
        name = newName
        keys = newKeys
        rows = newRows
        onChange(layout.copy(name = newName, keysJson = encodeKeyBarKeys(newKeys), rows = newRows))
    }

    OutlinedTextField(
        value = name,
        onValueChange = { persist(newName = it) },
        label = { Text("Layout name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Two rows")
            Text(
                // Says "already" rather than silently ignoring the switch: a layout with an arrows
                // cluster is two rows tall whatever this is set to (KeyBar's doc explains why), and
                // a toggle that visibly does nothing reads as a bug rather than as a no-op.
                if (keys.any { it.type == KeyBarKeyType.CURSOR_CLUSTER }) {
                    "Arrows already make this layout two rows tall."
                } else {
                    "Stack keys across two rows instead of one scrolling row."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = rows >= 2, onCheckedChange = { persist(newRows = if (it) 2 else 1) })
    }

    keys.forEachIndexed { index, key ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (key.type) {
                    KeyBarKeyType.MACRO -> "${key.label} (macro: ${key.macroText})"
                    // One row for four buttons, so the list has to say what it expands to -
                    // otherwise "Arrows" reads like a single key that has lost its glyph.
                    KeyBarKeyType.CURSOR_CLUSTER -> "${key.label} (cursor cluster: ↑ ↓ ← →)"
                    KeyBarKeyType.SPECIAL -> key.label
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    persist(
                        newKeys = keys.toMutableList().apply { add(index - 1, removeAt(index)) })
                },
                enabled = index > 0,
            ) { Text("Up") }
            TextButton(
                onClick = {
                    persist(
                        newKeys = keys.toMutableList().apply { add(index + 1, removeAt(index)) })
                },
                enabled = index < keys.lastIndex,
            ) { Text("Down") }
            TextButton(onClick = {
                persist(
                    newKeys = keys.toMutableList().apply { removeAt(index) })
            }) { Text("Remove") }
        }
    }

    TextButton(onClick = { addKeyOpen = true }) { Text("+ Add key") }

    if (addKeyOpen) {
        AddKeyDialog(
            onAddSpecial = { choice ->
                persist(
                    newKeys = keys + KeyBarKey(
                        choice.label,
                        KeyBarKeyType.SPECIAL,
                        keyCode = choice.keyCode
                    )
                ); addKeyOpen = false
            },
            onAddMacro = { label, text ->
                persist(
                    newKeys = keys + KeyBarKey(
                        label,
                        KeyBarKeyType.MACRO,
                        macroText = text
                    )
                ); addKeyOpen = false
            },
            onAddCluster = {
                persist(
                    newKeys = keys + KeyBarKey(
                        CURSOR_CLUSTER_LABEL,
                        KeyBarKeyType.CURSOR_CLUSTER
                    )
                ); addKeyOpen = false
            },
            onDismiss = { addKeyOpen = false },
        )
    }
}

/** A special keycode from [SPECIAL_KEY_CHOICES], the cursor cluster, or a macro's label and text. */
@Composable
private fun AddKeyDialog(
    onAddSpecial: (io.github.lordofpolls.shellwave.terminal.SpecialKeyChoice) -> Unit,
    onAddMacro: (label: String, text: String) -> Unit,
    onAddCluster: () -> Unit,
    onDismiss: () -> Unit,
) {
    var macroMode by remember { mutableStateOf(false) }
    var macroLabel by remember { mutableStateOf("") }
    var macroText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioRow(
                        selected = !macroMode,
                        onClick = { macroMode = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Special key")
                    }
                    RadioRow(
                        selected = macroMode,
                        onClick = { macroMode = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Macro")
                    }
                }
                if (macroMode) {
                    OutlinedTextField(
                        value = macroLabel,
                        onValueChange = { macroLabel = it },
                        label = { Text("Button label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = macroText,
                        onValueChange = { macroText = it },
                        label = { Text("Text to send") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        // Not a third radio mode: from the user's side it is just another thing to
                        // put on the bar, and a mode of its own would imply it needs configuring.
                        // First in the list because it is what most people reaching for an arrow
                        // want; the four separate arrows stay for anyone laying out their own row.
                        TextButton(onClick = onAddCluster) { Text("$CURSOR_CLUSTER_LABEL (cursor cluster: ↑ ↓ ← →)") }
                        SPECIAL_KEY_CHOICES.forEach { choice ->
                            TextButton(onClick = { onAddSpecial(choice) }) { Text(choice.label) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (macroMode) {
                TextButton(
                    onClick = { onAddMacro(macroLabel.ifBlank { macroText }, macroText) },
                    enabled = macroText.isNotBlank()
                ) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
