package io.github.lordofpolls.shellwave.feature.host

import android.content.ClipData
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.CredentialType
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.KeyBarLayoutDao
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.net.parseMacAddress
import io.github.lordofpolls.shellwave.feature.settings.ColorSchemeFields
import io.github.lordofpolls.shellwave.feature.settings.TerminalProfileFields
import io.github.lordofpolls.shellwave.ssh.GeneratedKey
import io.github.lordofpolls.shellwave.ssh.GeneratedKeyAlgorithm
import io.github.lordofpolls.shellwave.ssh.KeyEnrolment
import io.github.lordofpolls.shellwave.ssh.ScriptRunner
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.detectMacAddress
import io.github.lordofpolls.shellwave.ssh.generateKeyPair
import io.github.lordofpolls.shellwave.ssh.publicKeyLineOf
import io.github.lordofpolls.shellwave.ssh.readKeyText
import io.github.lordofpolls.shellwave.ssh.resolveProxyHops
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.ui.design.AdvancedSection
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.launch

private enum class AuthKind { PASSWORD, IMPORT_KEY, GENERATE_KEY, KEYBOARD_INTERACTIVE }

/**
 * Add or edit a host. Editing seals a fresh credential and repoints the host at it; the previous
 * credential row is left orphaned rather than chased down and deleted.
 *
 * [originalAuthKind] seeds the radio selection from the stored CredentialType. Without it the
 * screen always opened on `AuthKind.PASSWORD`, which left Save disabled for any non-password host
 * until a secret was re-entered. Generated and imported keys share one stored type, so both show as
 * "Import key".
 *
 * [keepExistingCredential] lets Save succeed without re-pasting a key or retyping an untouched
 * password. Resealing a private key would seal an empty one, since the decrypted material is never
 * held here.
 *
 * The state keying matters. `hostDao.observeAll()` is a Room `Flow`, so [existing] is `null` on the
 * first composition even when editing a saved host, and an unkeyed `remember {}` latches that null
 * forever - the form opens blank. That shipped twice. Every `existing`-derived value below is keyed
 * on `existing?.id` and declared in this composable's own scope, not inside [AdvancedSection]'s
 * lambda, whose content leaves composition when collapsed.
 */
@Composable
fun AddEditHostScreen(
    existing: HostEntity?,
    hostDao: HostDao,
    credentialVault: CredentialVault,
    keyEnrolment: KeyEnrolment,
    terminalProfileDao: TerminalProfileDao,
    colorSchemeDao: ColorSchemeDao,
    keyBarLayoutDao: KeyBarLayoutDao,
    portForwardDao: PortForwardDao,
    scriptRunner: ScriptRunner,
    sessionManager: SessionManager,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    // Keyed on existing?.id, never plain remember - see this screen's doc.
    var label by remember(existing?.id) { mutableStateOf(existing?.label.orEmpty()) }
    var hostname by remember(existing?.id) { mutableStateOf(existing?.hostname.orEmpty()) }
    var port by remember(existing?.id) { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember(existing?.id) { mutableStateOf(existing?.username.orEmpty()) }
    var requireBiometric by remember { mutableStateOf(false) }
    var resilientSession by remember(existing?.id) {
        mutableStateOf(
            existing?.resilientSession ?: false
        )
    }
    var macAddress by remember(existing?.id) { mutableStateOf(existing?.macAddress.orEmpty()) }
    var detectingMac by remember { mutableStateOf(false) }

    // Each override is its own dedicated row, shared with nothing. Null means "no override yet"; a
    // non-null value is already persisted, inserted the moment the checkbox is switched on, so its
    // id is exactly what save() writes into HostEntity.
    var terminalProfileOverride by remember(existing?.id) {
        mutableStateOf<TerminalProfileEntity?>(
            null
        )
    }
    var colorSchemeOverride by remember(existing?.id) { mutableStateOf<ColorSchemeEntity?>(null) }
    // The key bar points at one of the shared named layouts - a plain id, and no row this screen
    // owns.
    var keyBarLayoutId by remember(existing?.id) { mutableStateOf(existing?.keyBarLayoutId) }
    // Null means "connect directly". Cycle prevention stops at excluding this host itself: a
    // multi-hop cycle can only be seen by walking the whole chain, which resolveProxyChain already
    // does at connect time with a clear error.
    var proxyJumpHostId by remember(existing?.id) { mutableStateOf(existing?.proxyJumpHostId) }
    val allHosts by hostDao.observeAll().collectAsState(initial = emptyList())

    LaunchedEffect(existing?.id, existing?.terminalProfileId) {
        terminalProfileOverride =
            existing?.terminalProfileId?.let { terminalProfileDao.getById(it) }
    }
    LaunchedEffect(existing?.id, existing?.colorSchemeId) {
        colorSchemeOverride = existing?.colorSchemeId?.let { colorSchemeDao.getById(it) }
    }
    val keyBarLayouts by keyBarLayoutDao.observeAll().collectAsState(initial = emptyList())

    fun enableProfileOverride() {
        scope.launch {
            val fresh = DEFAULT_TERMINAL_PROFILE.copy(id = 0, name = "Host override")
            terminalProfileOverride = fresh.copy(id = terminalProfileDao.insert(fresh))
        }
    }

    fun disableProfileOverride() {
        val current = terminalProfileOverride ?: return
        terminalProfileOverride = null
        scope.launch { terminalProfileDao.delete(current) }
    }

    fun saveProfileOverride(updated: TerminalProfileEntity) {
        terminalProfileOverride = updated
        scope.launch { terminalProfileDao.update(updated) }
    }

    fun enableSchemeOverride() {
        scope.launch {
            val fresh = DEFAULT_COLOR_SCHEME.copy(id = 0, name = "Custom")
            colorSchemeOverride = fresh.copy(id = colorSchemeDao.insert(fresh))
        }
    }

    fun disableSchemeOverride() {
        val current = colorSchemeOverride ?: return
        colorSchemeOverride = null
        scope.launch { colorSchemeDao.delete(current) }
    }

    fun saveSchemeOverride(updated: ColorSchemeEntity) {
        colorSchemeOverride = updated
        scope.launch { colorSchemeDao.update(updated) }
    }

    var authKind by remember { mutableStateOf(AuthKind.PASSWORD) }
    var originalAuthKind by remember { mutableStateOf<AuthKind?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var password by remember { mutableStateOf("") }

    var importedPem by remember { mutableStateOf("") }
    var importedPassphrase by remember { mutableStateOf("") }
    var importedPublicKeyPreview by remember { mutableStateOf<String?>(null) }

    var generatedKey by remember { mutableStateOf<GeneratedKey?>(null) }

    LaunchedEffect(existing?.credentialId) {
        val credentialId = existing?.credentialId ?: return@LaunchedEffect
        val summary = credentialVault.describe(credentialId) ?: return@LaunchedEffect
        val kind =
            when (summary.type) {
                CredentialType.PASSWORD -> AuthKind.PASSWORD
                CredentialType.PRIVATE_KEY -> AuthKind.IMPORT_KEY
                CredentialType.KEYBOARD_INTERACTIVE -> AuthKind.KEYBOARD_INTERACTIVE
            }
        authKind = kind
        originalAuthKind = kind
    }

    // Editing, radio still on the stored credential's auth kind, nothing typed that would replace
    // it.
    val keepExistingCredential =
        existing != null && authKind == originalAuthKind &&
                when (authKind) {
                    AuthKind.PASSWORD -> password.isEmpty()
                    AuthKind.IMPORT_KEY -> importedPem.isBlank()
                    AuthKind.GENERATE_KEY -> generatedKey == null
                    AuthKind.KEYBOARD_INTERACTIVE -> true
                }

    val pickDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { readKeyText(activity.contentResolver, uri) }
                    .onSuccess { importedPem = it }
                    .onFailure { error = it.message }
            }
        }

    fun canSave(): Boolean =
        hostname.isNotBlank() && username.isNotBlank() && port.toIntOrNull() != null &&
                (keepExistingCredential ||
                        when (authKind) {
                            AuthKind.PASSWORD -> password.isNotEmpty()
                            AuthKind.IMPORT_KEY -> importedPublicKeyPreview != null
                            AuthKind.GENERATE_KEY -> generatedKey != null
                            AuthKind.KEYBOARD_INTERACTIVE -> true
                        })

    fun save() {
        val portInt = port.toIntOrNull() ?: return
        if (macAddress.isNotBlank() && parseMacAddress(macAddress) == null) {
            error = "\"$macAddress\" is not a MAC address - six hex pairs, like 3c:22:fb:01:02:03"
            return
        }
        scope.launch {
            try {
                val credentialId =
                    if (keepExistingCredential) {
                        existing!!.credentialId
                    } else {
                        when (authKind) {
                            AuthKind.PASSWORD -> credentialVault.storePassword(
                                password,
                                label.ifBlank { null },
                                requireBiometric,
                                activity
                            )

                            AuthKind.IMPORT_KEY ->
                                credentialVault.storePrivateKey(
                                    importedPem,
                                    importedPassphrase.ifBlank { null },
                                    importedPublicKeyPreview ?: return@launch,
                                    label.ifBlank { null },
                                    requireBiometric,
                                    activity,
                                )

                            AuthKind.GENERATE_KEY -> {
                                val key = generatedKey ?: return@launch
                                credentialVault.storePrivateKey(
                                    key.privateKeyPem,
                                    null,
                                    key.publicKeyLine,
                                    label.ifBlank { null },
                                    requireBiometric,
                                    activity
                                )
                            }

                            AuthKind.KEYBOARD_INTERACTIVE -> credentialVault.storeKeyboardInteractive(
                                label.ifBlank { null })
                        }
                    }
                val now = System.currentTimeMillis()
                if (existing == null) {
                    hostDao.insert(
                        HostEntity(
                            label = label.ifBlank { null },
                            hostname = hostname,
                            port = portInt,
                            username = username,
                            credentialId = credentialId,
                            lastConnectedAt = null,
                            createdAt = now,
                            resilientSession = resilientSession,
                            terminalProfileId = terminalProfileOverride?.id,
                            colorSchemeId = colorSchemeOverride?.id,
                            keyBarLayoutId = keyBarLayoutId,
                            proxyJumpHostId = proxyJumpHostId,
                            macAddress = macAddress.ifBlank { null },
                        ),
                    )
                } else {
                    hostDao.update(
                        existing.copy(
                            label = label.ifBlank { null },
                            hostname = hostname,
                            port = portInt,
                            username = username,
                            credentialId = credentialId,
                            resilientSession = resilientSession,
                            terminalProfileId = terminalProfileOverride?.id,
                            colorSchemeId = colorSchemeOverride?.id,
                            keyBarLayoutId = keyBarLayoutId,
                            proxyJumpHostId = proxyJumpHostId,
                            macAddress = macAddress.ifBlank { null },
                        ),
                    )
                }
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Failed to save host"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (existing == null) "Add host" else "Edit host",
            style = MaterialTheme.typography.headlineSmall
        )

        // Port lives in Advanced, pre-filled 22, so leaving it untouched is always valid.
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it },
            label = { Text("Hostname") },
            modifier = Modifier.fillMaxWidth()
        )
        // Without a declared content type a password manager sees an unlabelled field and offers
        // nothing. Username here and Password below give it the pair it needs.
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username },
        )

        Text("Authentication", style = MaterialTheme.typography.titleSmall)
        AuthKind.entries.forEach { kind ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = authKind == kind, onClick = { authKind = kind })
                Text(kind.label())
            }
        }

        when (authKind) {
            AuthKind.PASSWORD ->
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Password },
                )

            AuthKind.IMPORT_KEY -> {
                Button(onClick = { pickDocument.launch(arrayOf("*/*")) }) { Text("Pick key file") }
                // PuTTY .ppk has always worked - sshj registers PuTTYKeyFile and the picker takes
                // any MIME type - but nothing said so, and users asked for a feature already there.
                // Ed25519 .ppk is the one exception, refused with its own message.
                Text(
                    "OpenSSH, PEM/PKCS#8 or PuTTY .ppk.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = importedPem,
                    onValueChange = { importedPem = it; importedPublicKeyPreview = null },
                    label = { Text("Or paste private key") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = importedPassphrase,
                    onValueChange = { importedPassphrase = it; importedPublicKeyPreview = null },
                    label = { Text("Passphrase (if encrypted)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Password },
                )
                Button(
                    onClick = {
                        runCatching {
                            publicKeyLineOf(
                                importedPem,
                                importedPassphrase.ifBlank { null })
                        }
                            .onSuccess { importedPublicKeyPreview = it; error = null }
                            .onFailure { error = it.message ?: "Could not parse key" }
                    },
                    enabled = importedPem.isNotBlank(),
                ) { Text("Validate key") }
                // The parsed key line is machine truth; the "Parsed:" label in front of it is
                // prose.
                importedPublicKeyPreview?.let {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Parsed:", style = MaterialTheme.typography.bodySmall)
                        MachineText(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            AuthKind.GENERATE_KEY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        generatedKey = generateKeyPair(GeneratedKeyAlgorithm.ED25519)
                    }) { Text("Generate ed25519") }
                    Button(onClick = {
                        generatedKey = generateKeyPair(GeneratedKeyAlgorithm.RSA)
                    }) { Text("Generate RSA") }
                }
                generatedKey?.let { key ->
                    Text(
                        "Public key (add this to the server's authorized_keys):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    MachineText(key.publicKeyLine, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                androidx.compose.ui.platform.ClipEntry(
                                    ClipData.newPlainText("public key", key.publicKeyLine)
                                )
                            )
                        }
                    }) {
                        Text("Copy public key")
                    }
                }
            }

            AuthKind.KEYBOARD_INTERACTIVE ->
                Text(
                    "The server will prompt for whatever it needs (password, TOTP code, ...) when connecting.",
                    style = MaterialTheme.typography.bodySmall,
                )
        }

        if (authKind != AuthKind.KEYBOARD_INTERACTIVE) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = requireBiometric, onCheckedChange = { requireBiometric = it })
                Text("Require biometric unlock to use this credential")
            }
        }

        // Every var read below is `remember`'d above this call, not inside it: state has to survive
        // collapse and expand. See this file's doc.
        AdvancedSection(title = "Advanced") {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = macAddress,
                // Clears the rejection this field caused, so the red line cannot outlive a fix.
                onValueChange = { macAddress = it; error = null },
                label = { Text("MAC address (optional)") },
                placeholder = { Text("3c:22:fb:01:02:03") },
                supportingText = { Text("Adds a Wake-on-LAN action to this host's menu.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (existing != null) {
                TextButton(
                    enabled = !detectingMac,
                    onClick = {
                        detectingMac = true
                        error = null
                        val target =
                            existing.copy(
                                hostname = hostname.ifBlank { existing.hostname },
                                port = port.toIntOrNull() ?: existing.port,
                                username = username.ifBlank { existing.username },
                            )
                        scope.launch {
                            try {
                                detectMacAddress(
                                    scriptRunner,
                                    "Detect MAC: ${target.username}@${target.hostname}",
                                    target.hostname,
                                    target.port,
                                    target.username,
                                    credentialVault.resolve(target.credentialId, activity),
                                    resolveProxyHops(target, hostDao, credentialVault, activity),
                                ).fold({ macAddress = it }, { error = it.message })
                            } catch (e: Exception) {
                                error = e.message ?: "Could not detect the MAC address"
                            } finally {
                                detectingMac = false
                            }
                        }
                    },
                ) {
                    Text(if (detectingMac) "Asking the host..." else "Detect from host")
                }
            }

            Text("Resilient session", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = resilientSession, onCheckedChange = { resilientSession = it })
                Text("Reattach to the same shell after a reconnect (needs tmux on the server)")
            }
            Text(
                "Reconnects reattach to the same tmux session instead of starting fresh. Falls back to " +
                        "a plain shell if the server has no tmux.",
                style = MaterialTheme.typography.bodySmall,
            )

            // Each checkbox gates a dedicated row, reusing Settings' own field editors. Unchecked
            // means "use the app-wide default".
            Text("Overrides", style = MaterialTheme.typography.titleSmall)

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = terminalProfileOverride != null,
                    onCheckedChange = { checked -> if (checked) enableProfileOverride() else disableProfileOverride() },
                )
                Text("Override terminal profile for this host")
            }
            terminalProfileOverride?.let { profile ->
                TerminalProfileFields(
                    profile = profile,
                    onChange = ::saveProfileOverride
                )
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = colorSchemeOverride != null,
                    onCheckedChange = { checked -> if (checked) enableSchemeOverride() else disableSchemeOverride() },
                )
                Text("Override colour scheme for this host")
            }
            colorSchemeOverride?.let { scheme ->
                ColorSchemeFields(
                    scheme = scheme,
                    onChange = ::saveSchemeOverride
                )
            }

            Text("Key bar layout", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = keyBarLayoutId == null, onClick = { keyBarLayoutId = null })
                Text("Default (Esc, Tab, arrows)")
            }
            keyBarLayouts.forEach { layout ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(
                        selected = keyBarLayoutId == layout.id,
                        onClick = { keyBarLayoutId = layout.id })
                    Text(layout.name)
                }
            }

            // Excludes this host itself - an immediate one-host cycle is the only case cheap to
            // rule out.
            Text("Proxy jump (ProxyJump)", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(
                    selected = proxyJumpHostId == null,
                    onClick = { proxyJumpHostId = null })
                Text("Connect directly")
            }
            allHosts.filter { it.id != existing?.id }.forEach { jumpCandidate ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(
                        selected = proxyJumpHostId == jumpCandidate.id,
                        onClick = { proxyJumpHostId = jumpCandidate.id })
                    // A nickname is a human label; the fallback user@host:port is machine-asserted
                    // identity.
                    val jumpLabel = jumpCandidate.label
                    if (jumpLabel != null) {
                        Text(jumpLabel)
                    } else {
                        MachineText("${jumpCandidate.username}@${jumpCandidate.hostname}:${jumpCandidate.port}")
                    }
                }
            }
            Text(
                "Connects through another saved host acting as a bastion. Jump settings chain, so " +
                        "several hops can be reached this way.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (existing != null) {
                KeyEnrolmentSection(
                    host = existing,
                    credentialVault = credentialVault,
                    hostDao = hostDao,
                    keyEnrolment = keyEnrolment,
                    activity = activity
                )
                TunnelsSection(
                    host = existing,
                    portForwardDao = portForwardDao,
                    sessionManager = sessionManager
                )
            }
        }

        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Button(onClick = ::save, enabled = canSave()) { Text("Save") }
        }
    }
}

private fun AuthKind.label(): String =
    when (this) {
        AuthKind.PASSWORD -> "Password"
        AuthKind.IMPORT_KEY -> "Import key"
        AuthKind.GENERATE_KEY -> "Generate key"
        AuthKind.KEYBOARD_INTERACTIVE -> "Keyboard-interactive"
    }
