package io.github.lordofpolls.shellwave.feature.keys

import android.content.ClipData
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.CredentialType
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.feature.host.credentialLabel
import io.github.lordofpolls.shellwave.feature.settings.SettingsSwitch
import io.github.lordofpolls.shellwave.ssh.GeneratedKeyAlgorithm
import io.github.lordofpolls.shellwave.ssh.generateKeyPair
import io.github.lordofpolls.shellwave.ssh.parsePublicKeyLine
import io.github.lordofpolls.shellwave.ssh.publicKeyLineOf
import io.github.lordofpolls.shellwave.ssh.readKeyText
import io.github.lordofpolls.shellwave.ssh.sha256Fingerprint
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.FingerprintBlock
import io.github.lordofpolls.shellwave.ui.design.RadioRow
import io.github.lordofpolls.shellwave.ui.design.rememberFormState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Keys destination: private keys as standalone assets, independent of any host.
 *
 * Passwords and keyboard-interactive rows are filtered out because they are a host's own secret
 * with no management surface. Only the public half and its fingerprint are ever displayed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    credentialDao: CredentialDao,
    credentialVault: CredentialVault,
    hostDao: HostDao,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val keys = credentialDao.observeAll().collectAsState(initial = emptyList()).value
        .filter { it.type == CredentialType.PRIVATE_KEY.name }
    val hosts by hostDao.observeAll().collectAsState(initial = emptyList())

    var addMenuOpen by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CredentialEntity?>(null) }
    var deleting by remember { mutableStateOf<CredentialEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Keys") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = { addMenuOpen = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add key")
                    }
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Generate a key") },
                            onClick = { addMenuOpen = false; generating = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Import from file") },
                            onClick = { addMenuOpen = false; importing = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                )
            }
            if (keys.isEmpty()) {
                EmptyState(
                    message = "No keys yet.",
                    actionLabel = "Generate a key",
                    onAction = { generating = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(keys, key = { it.id }) { key ->
                        KeyCard(
                            key = key,
                            hostCount = hosts.count { it.credentialId == key.id },
                            onCopy = {
                                message = null
                                scope.launch {
                                    key.publicKeyText?.let {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("public key", it)))
                                    }
                                }
                            },
                            onRename = { message = null; renaming = key },
                            onDelete = {
                                val usedBy = hosts.filter { it.credentialId == key.id }
                                val blocked = keyDeleteBlockReason(credentialLabel(key), usedBy)
                                message = blocked
                                if (blocked == null) deleting = key
                            },
                        )
                    }
                }
            }
        }
    }

    if (generating) {
        AddKeyDialog(
            title = "Generate a key",
            confirmLabel = "Generate",
            onDismiss = { generating = false },
            onConfirm = { label, _, algorithm, requireBiometric ->
                runCatching {
                    val generated = withContext(Dispatchers.Default) { generateKeyPair(algorithm) }
                    credentialVault.storePrivateKey(
                        generated.privateKeyPem,
                        null,
                        generated.publicKeyLine,
                        label,
                        requireBiometric,
                        activity,
                    )
                }.exceptionOrNull()
            },
            algorithms = GeneratedKeyAlgorithm.entries,
        )
    }

    if (importing) {
        val pickedPem = remember { mutableStateOf("") }
        val pickError = remember { mutableStateOf<String?>(null) }
        val picker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    scope.launch {
                        runCatching { readKeyText(activity.contentResolver, uri) }
                            .onSuccess { pickedPem.value = it; pickError.value = null }
                            .onFailure { pickError.value = it.message }
                    }
                }
            }
        AddKeyDialog(
            title = "Import from file",
            confirmLabel = "Import",
            onDismiss = { importing = false },
            onConfirm = { label, passphrase, _, requireBiometric ->
                runCatching {
                    val publicKeyLine = publicKeyLineOf(pickedPem.value, passphrase)
                    credentialVault.storePrivateKey(
                        pickedPem.value,
                        passphrase,
                        publicKeyLine,
                        label,
                        requireBiometric,
                        activity,
                    )
                }.exceptionOrNull()
            },
            confirmEnabled = pickedPem.value.isNotBlank(),
            importPicker = { picker.launch(arrayOf("*/*")) },
            importPicked = pickedPem.value.isNotBlank(),
            initialError = pickError.value,
        )
    }

    renaming?.let { key ->
        var label by rememberFormState(key.id) { key.label.orEmpty() }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename key") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch {
                        runCatching { credentialDao.setLabel(key.id, label.ifBlank { null }) }
                            .onFailure { message = "Couldn't rename ${credentialLabel(key)}." }
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { key ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${credentialLabel(key)}?") },
            text = { Text("The private key is removed from this device. Public keys you've already registered elsewhere stay valid.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        // Re-checked against the database, not the observed host list: that list
                        // starts empty, so a tap in the first frames after this screen is composed
                        // would otherwise pass the block and hit the RESTRICT foreign key.
                        val inUse = hostDao.countHostsUsingCredential(key.id)
                        if (inUse > 0) {
                            message = keyDeleteBlockReason(
                                credentialLabel(key),
                                hosts.filter { it.credentialId == key.id },
                            ) ?: keyDeleteBlockReason(credentialLabel(key), inUse)
                            return@launch
                        }
                        runCatching { credentialDao.delete(key) }.onFailure {
                            // Not named as an in-use failure: the check above already ruled that
                            // out, so whatever is left is something this code does not know.
                            message = "Couldn't delete ${credentialLabel(key)}: ${it.message ?: "unknown error"}"
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyCard(
    key: CredentialEntity,
    hostCount: Int,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val publicKey = remember(key.publicKeyText) { key.publicKeyText?.let(::parsePublicKeyLine) }
    val fingerprint = remember(publicKey) { publicKey?.let(::sha256Fingerprint) }
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(credentialLabel(key), style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            key.publicKeyText?.substringBefore(' '),
                            if (hostCount == 0) "Unused" else "Used by $hostCount ${if (hostCount == 1) "host" else "hosts"}",
                            // A CA-signed key is accepted wherever that CA is trusted, not just by
                            // the hosts listed above, so the count alone understates its reach.
                            "Certificate".takeIf { key.certificate != null },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCopy, enabled = key.publicKeyText != null) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy public key")
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename key")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete key")
                }
            }
            if (fingerprint != null) {
                HorizontalDivider()
                FingerprintBlock(fingerprint = fingerprint)
            }
        }
    }
}

/**
 * Shared shell for generate and import. [onConfirm] returns the failure, or `null` on success,
 * and the dialog closes only on success.
 */
@Composable
private fun AddKeyDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (
        label: String?,
        passphrase: String?,
        algorithm: GeneratedKeyAlgorithm,
        requireBiometric: Boolean,
    ) -> Throwable?,
    algorithms: List<GeneratedKeyAlgorithm> = emptyList(),
    confirmEnabled: Boolean = true,
    importPicker: (() -> Unit)? = null,
    importPicked: Boolean = false,
    initialError: String? = null,
) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(GeneratedKeyAlgorithm.ED25519) }
    var requireBiometric by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                algorithms.forEach { option ->
                    RadioRow(selected = algorithm == option, onClick = { algorithm = option }) {
                        Text(option.name.replace('_', ' ').lowercase())
                    }
                }
                if (importPicker != null) {
                    TextButton(onClick = importPicker) {
                        Text(if (importPicked) "Key file loaded - pick another" else "Pick key file")
                    }
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase (if encrypted)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password },
                    )
                }
                SettingsSwitch(
                    title = "Require biometric unlock",
                    description = "Ask for a fingerprint before this key is used.",
                    checked = requireBiometric,
                    onCheckedChange = { requireBiometric = it },
                )
                (error ?: initialError)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = confirmEnabled && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val failure = onConfirm(
                            label.ifBlank { null },
                            passphrase.ifBlank { null },
                            algorithm,
                            requireBiometric,
                        )
                        busy = false
                        if (failure == null) onDismiss() else error = failure.message ?: "Could not store the key"
                    }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
