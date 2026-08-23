package io.github.lordofpolls.shellwave.feature.host

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.ssh.ParsedSshConfig
import io.github.lordofpolls.shellwave.ssh.ParsedSshHost
import io.github.lordofpolls.shellwave.ssh.parseSshConfig
import io.github.lordofpolls.shellwave.ssh.publicKeyLineOf
import io.github.lordofpolls.shellwave.ssh.readKeyText
import kotlinx.coroutines.launch

/** How a previewed entry gets its mandatory HostEntity.credentialId - see this file's class doc. */
private enum class EntryCredentialChoice { EXISTING, PASSWORD, IMPORT_KEY, KEYBOARD_INTERACTIVE }

/**
 * A plain observable holder instead of a data class copied on every keystroke, so each entry's card
 * recomposes independently.
 */
private class ImportEntryState(val parsed: ParsedSshHost, alreadyImported: Boolean) {
    var selected by mutableStateOf(!alreadyImported)
    val alreadyImported = alreadyImported

    // Editable overrides, pre-filled from the parsed value: nothing is trusted blindly, and the
    // user can fix a wrong port or an alias-derived hostname before anything is written.
    var hostNameOverride by mutableStateOf(parsed.hostName)
    var usernameOverride by mutableStateOf(parsed.user.orEmpty())
    var portOverride by mutableStateOf((parsed.port ?: 22).toString())

    var credentialChoice by mutableStateOf(EntryCredentialChoice.EXISTING)
    var existingCredentialId by mutableStateOf<Long?>(null)
    var password by mutableStateOf("")
    var importedPem by mutableStateOf("")
    var importedPassphrase by mutableStateOf("")
    var importedPublicKeyPreview by mutableStateOf<String?>(null)

    fun isReady(): Boolean =
        hostNameOverride.isNotBlank() && usernameOverride.isNotBlank() && portOverride.toIntOrNull() != null &&
                when (credentialChoice) {
                    EntryCredentialChoice.EXISTING -> existingCredentialId != null
                    EntryCredentialChoice.PASSWORD -> password.isNotEmpty()
                    EntryCredentialChoice.IMPORT_KEY -> importedPublicKeyPreview != null
                    EntryCredentialChoice.KEYBOARD_INTERACTIVE -> true
                }
}

/**
 * `~/.ssh/config` import. Picks a file via SAF, parses it with parseSshConfig, and shows every
 * literal (non-wildcard) `Host` entry for the user to inspect, edit and select before anything is
 * written.
 *
 * Every [HostEntity] needs a credential and a config file has none - at most an `IdentityFile` path
 * on a filesystem this app cannot read. So each entry's card carries its own picker, offering
 * `AddEditHostScreen`'s auth choices less "generate key" and the biometric toggle, both one edit
 * away afterwards: an existing vault credential, a new sealed password, an imported private key
 * (pasted or SAF-picked, never the path the entry's own `IdentityFile` named), or
 * keyboard-interactive with no secret at all.
 *
 * An entry with nothing chosen can still be ticked, but [performImport] skips it and says why
 * rather than writing a broken row or inventing a placeholder credential.
 */
@Composable
fun ImportSshConfigScreen(
    hostDao: HostDao,
    credentialDao: CredentialDao,
    credentialVault: CredentialVault,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()

    var readError by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<ParsedSshConfig?>(null) }
    val entries = remember { mutableStateListOf<ImportEntryState>() }
    var importSummary by remember { mutableStateOf<List<String>?>(null) }

    val existingHosts by hostDao.observeAll().collectAsState(initial = emptyList())
    val existingCredentials by credentialDao.observeAll().collectAsState(initial = emptyList())

    val pickDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                val text = readKeyText(activity.contentResolver, uri)
                parseSshConfig(text)
            }.onSuccess { config ->
                readError = null
                importSummary = null
                parsed = config
                entries.clear()
                entries += config.hosts.map { host ->
                    val duplicate = existingHosts.any {
                        it.hostname == host.hostName && it.port == (host.port
                            ?: 22) && it.username == host.user
                    }
                    ImportEntryState(host, alreadyImported = duplicate)
                }
            }.onFailure { readError = it.message ?: "Could not read that file" }
        }

    suspend fun resolveCredentialId(state: ImportEntryState): Long? =
        when (state.credentialChoice) {
            EntryCredentialChoice.EXISTING -> state.existingCredentialId
            EntryCredentialChoice.PASSWORD ->
                state.password.ifEmpty { null }?.let {
                    credentialVault.storePassword(
                        it,
                        state.parsed.alias,
                        requireBiometric = false,
                        activity
                    )
                }

            EntryCredentialChoice.IMPORT_KEY ->
                state.importedPublicKeyPreview?.let {
                    credentialVault.storePrivateKey(
                        state.importedPem,
                        state.importedPassphrase.ifBlank { null },
                        it,
                        state.parsed.alias,
                        requireBiometric = false,
                        activity
                    )
                }

            EntryCredentialChoice.KEYBOARD_INTERACTIVE -> credentialVault.storeKeyboardInteractive(
                state.parsed.alias
            )
        }

    /**
     * Two passes, because `ProxyJump` can forward-reference an entry defined later in the file: the
     * referenced host's new row id is not known until its own insert has happened. Pass one inserts
     * every ready, selected entry with `proxyJumpHostId = null`; pass two repoints each inserted host
     * at its jump target's freshly-assigned id, but only among hosts this same batch created. An alias
     * matching a host from a previous import, or one the user did not select, is reported as "not
     * selected" instead of silently resolved against an unrelated row.
     */
    fun performImport() {
        scope.launch {
            val aliasToNewId = mutableMapOf<String, Long>()
            val summary = mutableListOf<String>()
            for (state in entries) {
                if (!state.selected) continue
                if (!state.isReady()) {
                    summary += "${state.parsed.alias}: skipped - no credential chosen"
                    continue
                }
                val credentialId = resolveCredentialId(state)
                if (credentialId == null) {
                    summary += "${state.parsed.alias}: skipped - no credential chosen"
                    continue
                }
                val newId =
                    hostDao.insert(
                        HostEntity(
                            label = state.parsed.alias,
                            hostname = state.hostNameOverride,
                            port = state.portOverride.toIntOrNull() ?: 22,
                            username = state.usernameOverride,
                            credentialId = credentialId,
                            lastConnectedAt = null,
                            createdAt = System.currentTimeMillis(),
                            proxyJumpHostId = null,
                        ),
                    )
                aliasToNewId[state.parsed.alias] = newId
                summary += "${state.parsed.alias}: imported"
            }
            for (state in entries) {
                if (!state.selected) continue
                val newId = aliasToNewId[state.parsed.alias] ?: continue
                val jumpAlias = state.parsed.proxyJump ?: continue
                val jumpId = aliasToNewId[jumpAlias] ?: continue
                val host = hostDao.getById(newId) ?: continue
                hostDao.update(host.copy(proxyJumpHostId = jumpId))
            }
            importSummary = summary
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Import from ~/.ssh/config", style = MaterialTheme.typography.headlineSmall)

        if (importSummary == null) {
            Button(onClick = { pickDocument.launch(arrayOf("*/*")) }) { Text(if (parsed == null) "Pick config file" else "Pick a different file") }
            readError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            parsed?.let { config ->
                if (config.includeDirectives.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Include not followed",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "This file references other files Shellwave can't open (a picked file only grants access to itself). " +
                                        "Hosts inside them won't appear below:",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            config.includeDirectives.forEach {
                                Text(
                                    "Include $it",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (config.hosts.isEmpty()) {
                    Text(
                        "No importable Host entries found in this file.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "${entries.count { it.selected }} of ${entries.size} selected",
                        style = MaterialTheme.typography.titleSmall
                    )
                    entries.forEach { state ->
                        ImportEntryCard(
                            state = state,
                            allEntries = entries,
                            existingCredentials = existingCredentials
                        )
                    }
                    Button(
                        onClick = ::performImport,
                        enabled = entries.any { it.selected && it.isReady() }) {
                        Text("Import selected (${entries.count { it.selected && it.isReady() }} ready)")
                    }
                }
            }
        } else {
            Text("Import complete", style = MaterialTheme.typography.titleSmall)
            importSummary!!.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone) { Text(if (importSummary == null) "Cancel" else "Done") }
        }
    }
}

/** Recomputed live against the current selection - see [performImport] for what "resolved" means. */
private fun proxyJumpStatusFor(
    state: ImportEntryState,
    allEntries: List<ImportEntryState>
): String? {
    val targetAlias = state.parsed.proxyJump ?: return null
    val target = allEntries.firstOrNull { it.parsed.alias == targetAlias }
    return when {
        target == null -> "Proxy jump \"$targetAlias\" does not match any Host entry in this file - will connect directly."
        !target.selected -> "Proxy jump \"$targetAlias\" was not selected for import - will connect directly."
        else -> "Proxy jump: through \"$targetAlias\" (linked automatically once both are imported)."
    }
}

private fun credentialLabel(entity: CredentialEntity): String =
    entity.label ?: "${entity.type.lowercase().replace('_', ' ')} credential #${entity.id}"

@Composable
private fun ImportEntryCard(
    state: ImportEntryState,
    allEntries: List<ImportEntryState>,
    existingCredentials: List<CredentialEntity>
) {
    val activity = LocalContext.current as FragmentActivity
    val pickKeyFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { readKeyText(activity.contentResolver, uri) }
                    .onSuccess { state.importedPem = it; state.importedPublicKeyPreview = null }
            }
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.selected, onCheckedChange = { state.selected = it })
                Column {
                    Text(state.parsed.alias, style = MaterialTheme.typography.titleSmall)
                    if (state.alreadyImported) {
                        Text(
                            "Matches an already-saved host, so left unchecked. Tick to import a duplicate anyway.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (!state.selected) return@Column

            OutlinedTextField(
                value = state.hostNameOverride,
                onValueChange = { state.hostNameOverride = it },
                label = { Text("Hostname") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.portOverride,
                onValueChange = { state.portOverride = it },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.usernameOverride,
                onValueChange = { state.usernameOverride = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.parsed.identityFiles.isNotEmpty()) {
                Text(
                    "IdentityFile ${state.parsed.identityFiles.joinToString(", ")} is ignored - Shellwave won't read a key off disk. " +
                            "Choose or import a credential below.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.parsed.compression != null || state.parsed.serverAliveInterval != null) {
                Text(
                    buildString {
                        append("Parsed but not applied (no matching Shellwave setting yet): ")
                        val parts = mutableListOf<String>()
                        state.parsed.compression?.let { parts += "Compression ${if (it) "yes" else "no"}" }
                        state.parsed.serverAliveInterval?.let { parts += "ServerAliveInterval $it" }
                        append(parts.joinToString(", "))
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            proxyJumpStatusFor(state, allEntries)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()
            Text("Credential", style = MaterialTheme.typography.labelLarge)
            EntryCredentialChoice.entries.forEach { choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.credentialChoice == choice,
                        onClick = { state.credentialChoice = choice })
                    Text(choice.label())
                }
            }
            when (state.credentialChoice) {
                EntryCredentialChoice.EXISTING ->
                    if (existingCredentials.isEmpty()) {
                        Text(
                            "No saved credentials yet - add a password or key below instead.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        existingCredentials.forEach { credential ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = state.existingCredentialId == credential.id,
                                    onClick = { state.existingCredentialId = credential.id })
                                Text(credentialLabel(credential))
                            }
                        }
                    }

                EntryCredentialChoice.PASSWORD ->
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { state.password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )

                EntryCredentialChoice.IMPORT_KEY -> {
                    Button(onClick = { pickKeyFile.launch(arrayOf("*/*")) }) { Text("Pick key file") }
                    // Same list as AddEditHostScreen; this screen imports keys the same way.
                    Text(
                        "OpenSSH, PEM/PKCS#8 or PuTTY .ppk.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = state.importedPem,
                        onValueChange = {
                            state.importedPem = it; state.importedPublicKeyPreview = null
                        },
                        label = { Text("Or paste private key") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    OutlinedTextField(
                        value = state.importedPassphrase,
                        onValueChange = {
                            state.importedPassphrase = it; state.importedPublicKeyPreview = null
                        },
                        label = { Text("Passphrase (if encrypted)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    var keyError by remember { mutableStateOf<String?>(null) }
                    Button(
                        onClick = {
                            runCatching {
                                publicKeyLineOf(
                                    state.importedPem,
                                    state.importedPassphrase.ifBlank { null })
                            }
                                .onSuccess { state.importedPublicKeyPreview = it; keyError = null }
                                .onFailure { keyError = it.message ?: "Could not parse key" }
                        },
                        enabled = state.importedPem.isNotBlank(),
                    ) { Text("Validate key") }
                    state.importedPublicKeyPreview?.let {
                        Text(
                            "Parsed: $it",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    keyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }

                EntryCredentialChoice.KEYBOARD_INTERACTIVE ->
                    Text(
                        "The server will prompt for whatever it needs when connecting.",
                        style = MaterialTheme.typography.bodySmall
                    )
            }

            if (!state.isReady()) {
                Text(
                    "Needs a credential (and a non-blank hostname/username/port) before it can be imported.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun EntryCredentialChoice.label(): String =
    when (this) {
        EntryCredentialChoice.EXISTING -> "Use an existing saved credential"
        EntryCredentialChoice.PASSWORD -> "Set a new password"
        EntryCredentialChoice.IMPORT_KEY -> "Import a private key"
        EntryCredentialChoice.KEYBOARD_INTERACTIVE -> "Keyboard-interactive"
    }
