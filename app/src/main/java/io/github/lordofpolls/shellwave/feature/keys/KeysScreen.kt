package io.github.lordofpolls.shellwave.feature.keys

import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
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
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.feature.settings.SettingsSwitch
import io.github.lordofpolls.shellwave.ssh.GeneratedKeyAlgorithm
import io.github.lordofpolls.shellwave.ssh.generateKeyPair
import io.github.lordofpolls.shellwave.ssh.parsePublicKeyLine
import io.github.lordofpolls.shellwave.ssh.publicKeyLineOf
import io.github.lordofpolls.shellwave.ssh.readKeyText
import io.github.lordofpolls.shellwave.ssh.sha256Fingerprint
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.FingerprintBlock
import io.github.lordofpolls.shellwave.ui.design.LocalReadableInset
import io.github.lordofpolls.shellwave.ui.design.RadioRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val hostsOrNull by hostDao.observeAll().collectAsState<List<HostEntity>, List<HostEntity>?>(initial = null)
    val hosts = hostsOrNull.orEmpty()

    var addMenuOpen by rememberSaveable { mutableStateOf(false) }
    var generating by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var removingUnused by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            message = null
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Keys") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    // Kept out of the "Add key" menu: a removal is not where anyone looks for one.
                    if (hostsOrNull != null && unusedKeys(keys, hosts).isNotEmpty()) {
                        TextButton(onClick = { removingUnused = true }) { Text("Remove unused") }
                    }
                    IconButton(onClick = { addMenuOpen = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add key")
                    }
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Generate a key") },
                            onClick = { addMenuOpen = false; generating = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Import a key") },
                            onClick = { addMenuOpen = false; importing = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (keys.isEmpty()) {
                EmptyState(
                    message = "No keys yet. Once you have one, pick it as a host's login in the host editor.",
                    actionLabel = "Generate a key",
                    onAction = { generating = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp + LocalReadableInset.current, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(keys, key = { it.id }) { key ->
                        KeyCard(
                            key = key,
                            usage = keyUsageSummary(key.id, hosts),
                            onCopy = {
                                message = null
                                scope.launch {
                                    key.publicKeyText?.let {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("public key", it)))
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message = "Public key copied."
                                    }
                                }
                            },
                            onShare = {
                                key.publicKeyText?.let {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, it)
                                    }
                                    activity.startActivity(Intent.createChooser(send, null))
                                }
                            },
                            onRename = { message = null; renamingId = key.id },
                            onDelete = {
                                val usedBy = hosts.filter { it.credentialId == key.id }
                                val blocked = keyDeleteBlockReason(credentialLabel(key), usedBy)
                                message = blocked
                                if (blocked == null) deletingId = key.id
                            },
                        )
                    }
                    item {
                        Text(
                            "Pick a key as a host's login in the host editor.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            onConfirm = { label, _, _, algorithm, requireBiometric ->
                runCatching {
                    val generated = withContext(Dispatchers.Default) { generateKeyPair(algorithm, label) }
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
        AddKeyDialog(
            title = "Import a key",
            confirmLabel = "Import",
            onDismiss = { importing = false },
            onConfirm = { label, pem, passphrase, _, requireBiometric ->
                runCatching {
                    val publicKeyLine = publicKeyLineOf(pem, passphrase, label)
                    credentialVault.storePrivateKey(
                        pem,
                        passphrase,
                        publicKeyLine,
                        label,
                        requireBiometric,
                        activity,
                    )
                }.exceptionOrNull()
            },
            importing = true,
        )
    }

    keys.firstOrNull { it.id == renamingId }?.let { key ->
        var label by rememberSaveable(key.id) { mutableStateOf(key.label.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renamingId = null },
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
                    renamingId = null
                    scope.launch {
                        runCatching { credentialDao.setLabel(key.id, label.ifBlank { null }) }
                            .onFailure { message = "Couldn't rename ${credentialLabel(key)}." }
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel") } },
        )
    }

    if (removingUnused) {
        // Fresh queries, not the observed lists - see HostDao.countHostsUsingCredential.
        val unused by produceState<List<CredentialEntity>?>(null) {
            val privateKeys = credentialDao.observeAll().first().filter { it.type == CredentialType.PRIVATE_KEY.name }
            value = unusedKeys(privateKeys, hostDao.observeAll().first())
        }
        val listed = unused
        AlertDialog(
            onDismissRequest = { removingUnused = false },
            title = { Text("Remove unused keys?") },
            text = {
                Text(
                    when {
                        listed == null -> "Checking which keys are unused…"
                        listed.isEmpty() -> "Every key is in use now. Nothing to remove."
                        else -> listed.joinToString("\n") { credentialLabel(it) } +
                            "\n\nThese private keys are deleted from this device for good. That includes " +
                            "keys picked only on hosts with forwarding off, and keys you haven't used yet. " +
                            "Public keys you've already registered elsewhere stay valid."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !listed.isNullOrEmpty(),
                    onClick = {
                        removingUnused = false
                        scope.launch {
                            val removable = unusedKeys(listed.orEmpty(), hostDao.observeAll().first())
                            val gainedUse = listed.orEmpty().size - removable.size
                            val failures = removable.mapNotNull { key ->
                                runCatching { credentialDao.delete(key) }.exceptionOrNull()?.let { credentialLabel(key) to it }
                            }
                            message = listOfNotNull(
                                "Kept $gainedUse that gained a use since you looked.".takeIf { gainedUse > 0 },
                                failures.firstOrNull()?.let { (label, e) ->
                                    "Couldn't delete $label: ${e.message}" +
                                        if (failures.size > 1) " (and ${failures.size - 1} more)" else ""
                                },
                            ).joinToString(" ").ifEmpty { null }
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingUnused = false }) { Text("Cancel") } },
        )
    }

    keys.firstOrNull { it.id == deletingId }?.let { key ->
        // A fresh query, not the observed list - see HostDao.countHostsUsingCredential.
        val forwardedBy by produceState<List<HostEntity>?>(null, key.id) {
            value = hostsForwardingKey(key.id, hostDao.observeAll().first())
        }
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Delete ${credentialLabel(key)}?") },
            text = {
                Text(
                    listOfNotNull(
                        "The private key is removed from this device. Public keys you've already registered elsewhere stay valid.",
                        forwardedBy?.let(::keyDeleteForwardingWarning),
                    ).joinToString("\n\n"),
                )
            },
            confirmButton = {
                TextButton(enabled = forwardedBy != null, onClick = {
                    deletingId = null
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
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyCard(
    key: CredentialEntity,
    usage: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val publicKey = remember(key.publicKeyText) { key.publicKeyText?.let(::parsePublicKeyLine) }
    val fingerprint = remember(publicKey) { publicKey?.let(::sha256Fingerprint) }
    val name = credentialLabel(key)
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        // A CA-signed key is accepted wherever that CA is trusted, not just by the
                        // hosts counted here, so the count alone understates its reach.
                        listOf(keyDescription(key), usage).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename $name")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete $name")
                }
            }
            if (fingerprint != null) {
                HorizontalDivider()
                FingerprintBlock(fingerprint = fingerprint)
            }
            Row {
                TextButton(
                    onClick = onCopy,
                    enabled = key.publicKeyText != null,
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                    modifier = Modifier.semantics { contentDescription = "Copy public key of $name" },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Copy public key")
                }
                TextButton(
                    onClick = onShare,
                    enabled = key.publicKeyText != null,
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                    modifier = Modifier.semantics { contentDescription = "Share public key of $name" },
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Share")
                }
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
        privateKey: String,
        passphrase: String?,
        algorithm: GeneratedKeyAlgorithm,
        requireBiometric: Boolean,
    ) -> Throwable?,
    algorithms: List<GeneratedKeyAlgorithm> = emptyList(),
    importing: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var privateKey by remember { mutableStateOf("") }
    var pickError by remember { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching { readKeyText(context.contentResolver, uri) }
                        .onSuccess { privateKey = it; pickError = null }
                        .onFailure { pickError = it.message }
                }
            }
        }
    var label by rememberSaveable { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var algorithm by rememberSaveable { mutableStateOf(GeneratedKeyAlgorithm.ED25519) }
    var requireBiometric by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                algorithms.forEach { option ->
                    RadioRow(selected = algorithm == option, onClick = { algorithm = option }) {
                        Text(option.label)
                    }
                }
                if (importing) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick key file") }
                    Text("OpenSSH, PEM/PKCS#8 or PuTTY .ppk.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it; pickError = null },
                        label = { Text("Or paste private key") },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                (error ?: pickError)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (!importing || privateKey.isNotBlank()) && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val failure = onConfirm(
                            label.ifBlank { null },
                            privateKey,
                            passphrase.ifBlank { null },
                            algorithm,
                            requireBiometric,
                        )
                        busy = false
                        if (failure == null) onDismiss() else error = if (importing) keyImportErrorMessage(failure) else failure.message ?: "Could not store the key"
                    }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
