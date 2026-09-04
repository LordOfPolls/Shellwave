package io.github.lordofpolls.shellwave.feature.host

import android.content.ClipData
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.github.lordofpolls.shellwave.feature.settings.SettingsRadioGroup
import io.github.lordofpolls.shellwave.feature.settings.SettingsRow
import io.github.lordofpolls.shellwave.feature.settings.SettingsSectionHeader
import io.github.lordofpolls.shellwave.feature.settings.SettingsSwitch
import io.github.lordofpolls.shellwave.feature.settings.TerminalProfileFields
import com.hierynomus.sshj.userauth.certificate.Certificate
import io.github.lordofpolls.shellwave.ssh.AuthMethod
import io.github.lordofpolls.shellwave.ssh.GeneratedKey
import io.github.lordofpolls.shellwave.ssh.GeneratedKeyAlgorithm
import io.github.lordofpolls.shellwave.ssh.KeyEnrolment
import io.github.lordofpolls.shellwave.ssh.ScriptRunner
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.detectMacAddress
import io.github.lordofpolls.shellwave.ssh.generateKeyPair
import io.github.lordofpolls.shellwave.ssh.loadKeysWithCertificate
import io.github.lordofpolls.shellwave.ssh.parsesAsCertificate
import io.github.lordofpolls.shellwave.ssh.publicKeyLineOf
import io.github.lordofpolls.shellwave.ssh.readKeyText
import io.github.lordofpolls.shellwave.ssh.resolveProxyHops
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.ui.design.BackTopBar
import io.github.lordofpolls.shellwave.ui.design.CollapsibleSection
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.rememberFormState
import kotlinx.coroutines.launch

private enum class AuthKind { PASSWORD, IMPORT_KEY, GENERATE_KEY, KEYBOARD_INTERACTIVE }

private enum class OverrideEditor(val title: String) {
    TERMINAL_PROFILE("Terminal profile"),
    COLOUR_SCHEME("Colour scheme"),
}

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
 * held here. It does not apply to a credential with nothing sealed at all - `ConfigImporter` makes
 * those - or Save would quietly keep a row that cannot connect.
 *
 * The state keying matters. `hostDao.observeAll()` is a Room `Flow`, so [existing] is `null` on the
 * first composition even when editing a saved host, and an unkeyed `remember {}` latches that null
 * forever - the form opens blank. That shipped twice. Every `existing`-derived value below is keyed
 * on `existing?.id` and declared in this composable's own scope, not inside a section's lambda,
 * whose content leaves composition when collapsed.
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
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    // Keyed on existing?.id, never plain remember - see this screen's doc.
    var label by rememberFormState(existing?.id) { existing?.label.orEmpty() }
    var hostname by rememberFormState(existing?.id) { existing?.hostname.orEmpty() }
    var port by rememberFormState(existing?.id) { (existing?.port ?: 22).toString() }
    var username by rememberFormState(existing?.id) { existing?.username.orEmpty() }
    var requireBiometric by rememberFormState(existing?.id) { false }
    var resilientSession by rememberFormState(existing?.id) { existing?.resilientSession ?: false }
    var macAddress by rememberFormState(existing?.id) { existing?.macAddress.orEmpty() }
    var detectingMac by remember { mutableStateOf(false) }
    var overrideEditor by rememberFormState<OverrideEditor?>(existing?.id) { null }

    // Each override is its own dedicated row, shared with nothing. Null means "no override yet"; a
    // non-null value is already persisted, inserted the moment the toggle is switched on, so its
    // id is exactly what save() writes into HostEntity.
    var terminalProfileOverride by rememberFormState<TerminalProfileEntity?>(existing?.id) { null }
    var colorSchemeOverride by rememberFormState<ColorSchemeEntity?>(existing?.id) { null }
    // The key bar points at one of the shared named layouts - a plain id, and no row this screen
    // owns.
    var keyBarLayoutId by rememberFormState(existing?.id) { existing?.keyBarLayoutId }
    // Null means "connect directly". Cycle prevention stops at excluding this host itself: a
    // multi-hop cycle can only be seen by walking the whole chain, which resolveProxyChain already
    // does at connect time with a clear error.
    var proxyJumpHostId by rememberFormState(existing?.id) { existing?.proxyJumpHostId }
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
    // Seeded from the stored credential's own certificate when editing (see the LaunchedEffect
    // below); certificateTouched tracks whether the user actually picked or cleared one this
    // session, so save() knows whether to write it back at all.
    var importedCertificate by rememberFormState<String?>(existing?.id) { null }
    var certificateTouched by rememberFormState(existing?.id) { false }
    var certificateError by remember { mutableStateOf<String?>(null) }
    var certificateNote by remember { mutableStateOf<String?>(null) }
    // The stored key's own public line, for the ed25519 certificate note when editing without
    // having re-typed the key (so importedPublicKeyPreview is still empty).
    var existingPublicKeyText by rememberFormState<String?>(existing?.id) { null }

    var generatedKey by remember { mutableStateOf<GeneratedKey?>(null) }

    // A config import creates credentials with no sealed secret, and this is the screen that fills
    // one in. Assumed present until described, so an existing host never flashes the notice below.
    var storedSecretPresent by rememberFormState(existing?.id) { true }

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
        storedSecretPresent = summary.hasStoredSecret
        requireBiometric = summary.requireBiometric
        importedCertificate = summary.certificate
        existingPublicKeyText = summary.publicKeyText
    }

    // Editing, radio still on the stored credential's auth kind, nothing typed that would replace
    // it - and something actually stored to keep.
    val keepExistingCredential =
        existing != null && authKind == originalAuthKind && storedSecretPresent &&
                when (authKind) {
                    AuthKind.PASSWORD -> password.isEmpty()
                    AuthKind.IMPORT_KEY -> importedPem.isBlank()
                    AuthKind.GENERATE_KEY -> generatedKey == null
                    AuthKind.KEYBOARD_INTERACTIVE -> true
                }

    // A rotated key must not silently carry over a certificate picked for the old one.
    fun setImportedPem(pem: String) {
        importedPem = pem
        if (!certificateTouched) importedCertificate = null
    }

    val pickDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching { readKeyText(activity.contentResolver, uri) }
                        .onSuccess { setImportedPem(it) }
                        .onFailure { error = it.message }
                }
            }
        }

    val pickCertificate =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                certificateNote = null
                val text = runCatching { readKeyText(activity.contentResolver, uri) }
                    .onFailure { certificateError = it.message ?: "Could not read that file" }
                    .getOrNull() ?: return@launch

                when {
                    // Load key and certificate together the way SshAuth will; sshj does not check
                    // they match, only that the pair loads. The standalone parse fallback exists
                    // because sshj drops a certificate on an ed25519 key (see loadKeysWithCertificate).
                    importedPem.isNotBlank() -> {
                        val matches = runCatching {
                            loadKeysWithCertificate(
                                importedPem,
                                text,
                                importedPassphrase.ifBlank { null },
                            ).public is Certificate<*>
                        }.getOrDefault(false)
                        if (matches || parsesAsCertificate(text)) {
                            importedCertificate = text.trim(); certificateTouched = true; certificateError = null
                        } else {
                            certificateError = "Not an OpenSSH certificate"
                        }
                    }

                    // Editing, key unchanged: decrypt the stored PEM to validate against it. Either a
                    // biometric-gated key that can't be unsealed right now, or the ed25519 key-match
                    // limitation above, falls back to a standalone parse - the certificate/key match
                    // is then only checked for real at connect time.
                    keepExistingCredential -> {
                        val authMethod =
                            runCatching { credentialVault.resolve(existing!!.credentialId, activity) }
                                .getOrNull() as? AuthMethod.PrivateKey
                        val matches = authMethod != null && runCatching {
                            loadKeysWithCertificate(
                                authMethod.privateKeyPem,
                                text,
                                authMethod.passphrase,
                            ).public is Certificate<*>
                        }.getOrDefault(false)
                        if (matches) {
                            importedCertificate = text.trim(); certificateTouched = true; certificateError = null
                        } else if (parsesAsCertificate(text)) {
                            importedCertificate = text.trim(); certificateTouched = true; certificateError = null
                            certificateNote = "Could not confirm this certificate matches the stored key - the match is checked at connect."
                        } else {
                            certificateError = "Not an OpenSSH certificate"
                        }
                    }

                    else -> certificateError = "Load the private key first."
                }
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
                        if (certificateTouched) {
                            credentialVault.setCertificate(existing!!.credentialId, importedCertificate)
                        }
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
                                    importedCertificate,
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

    val editor = overrideEditor
    if (editor != null) {
        BackHandler { overrideEditor = null }
        Column(modifier = modifier.fillMaxSize()) {
            BackTopBar(title = editor.title, onBack = { overrideEditor = null })
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (editor) {
                    OverrideEditor.TERMINAL_PROFILE ->
                        terminalProfileOverride?.let {
                            TerminalProfileFields(profile = it, onChange = ::saveProfileOverride)
                        }

                    OverrideEditor.COLOUR_SCHEME ->
                        colorSchemeOverride?.let {
                            ColorSchemeFields(scheme = it, onChange = ::saveSchemeOverride)
                        }
                }
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(
            title = if (existing == null) "Add host" else "Edit host",
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

            SettingsSectionHeader("Authentication")
            if (!storedSecretPresent) {
                Text(
                    "This host came from an imported configuration, which carries no secrets. Enter " +
                            "its password or key below before connecting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column {
                SettingsRadioGroup(
                    label = null,
                    options = AuthKind.entries,
                    selected = authKind,
                    labelOf = { it.label() },
                    onSelect = { authKind = it },
                )
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
                        onValueChange = { setImportedPem(it); importedPublicKeyPreview = null },
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Parsed:", style = MaterialTheme.typography.bodySmall)
                            MachineText(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { pickCertificate.launch(arrayOf("*/*")) }) {
                            Text("Certificate (optional)")
                        }
                        if (importedCertificate != null) {
                            TextButton(onClick = {
                                importedCertificate = null
                                certificateTouched = true
                                certificateError = null
                                certificateNote = null
                            }) {
                                Text("Clear")
                            }
                        }
                    }
                    certificateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    certificateNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (importedCertificate != null) {
                        Text("Certificate loaded - offered instead of the bare key.", style = MaterialTheme.typography.bodySmall)
                        // sshj 0.40.0 can't attach a certificate to an ed25519 openssh-key-v1 PEM
                        // loaded from a string - see KeyImport.loadKeysWithCertificate's KDoc.
                        if ((importedPublicKeyPreview ?: existingPublicKeyText)?.startsWith("ssh-ed25519 ") == true) {
                            Text(
                                "ed25519 keys can't carry a certificate in this app yet (a known sshj limitation) - it will be ignored.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                AuthKind.GENERATE_KEY -> {
                    Text("Generate:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            generatedKey = generateKeyPair(GeneratedKeyAlgorithm.ED25519)
                        }) { Text("ed25519") }
                        Button(onClick = {
                            generatedKey = generateKeyPair(GeneratedKeyAlgorithm.RSA)
                        }) { Text("RSA") }
                        Button(onClick = {
                            generatedKey = generateKeyPair(GeneratedKeyAlgorithm.ECDSA_P256)
                        }) { Text("ECDSA") }
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
                                    ClipEntry(
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
                SettingsSwitch(
                    title = "Require biometric unlock",
                    description =
                        if (keepExistingCredential) {
                            "Enter the password or key again to change this."
                        } else {
                            "Ask for a fingerprint before this credential is used."
                        },
                    checked = requireBiometric,
                    onCheckedChange = { requireBiometric = it },
                    enabled = !keepExistingCredential,
                )
            }

            // Every var read below is `remember`'d above this call, not inside it: state has to
            // survive collapse and expand. See this file's doc.
            CollapsibleSection("Connection") {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Excludes this host itself - an immediate one-host cycle is the only case cheap to
                // rule out.
                val jumpHost = allHosts.firstOrNull { it.id == proxyJumpHostId }
                PickerRow(
                    title = "Proxy jump (ProxyJump)",
                    value = {
                        if (jumpHost == null) DimText("Connect directly") else HostChoice(jumpHost)
                    },
                ) { dismiss ->
                    DropdownMenuItem(
                        text = { Text("Connect directly") },
                        onClick = { proxyJumpHostId = null; dismiss() },
                    )
                    allHosts.filter { it.id != existing?.id }.forEach { candidate ->
                        DropdownMenuItem(
                            text = { HostChoice(candidate) },
                            onClick = { proxyJumpHostId = candidate.id; dismiss() },
                        )
                    }
                }
                Text(
                    "Connects through another saved host acting as a bastion. Jump settings chain, so " +
                            "several hops can be reached this way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SettingsSwitch(
                    title = "Resilient session",
                    description = "Reattach to the same tmux session after a reconnect. Falls back " +
                            "to a plain shell without tmux.",
                    checked = resilientSession,
                    onCheckedChange = { resilientSession = it },
                )
            }

            CollapsibleSection("Appearance") {
                SettingsSwitch(
                    title = "Terminal profile override",
                    description = "Font, cursor and scrollback for this host only.",
                    checked = terminalProfileOverride != null,
                    onCheckedChange = { on -> if (on) enableProfileOverride() else disableProfileOverride() },
                )
                if (terminalProfileOverride != null) {
                    SettingsRow(
                        title = "Edit terminal profile",
                        chevron = true,
                        onClick = { overrideEditor = OverrideEditor.TERMINAL_PROFILE },
                    )
                }

                SettingsSwitch(
                    title = "Colour scheme override",
                    description = "A different palette for this host only.",
                    checked = colorSchemeOverride != null,
                    onCheckedChange = { on -> if (on) enableSchemeOverride() else disableSchemeOverride() },
                )
                if (colorSchemeOverride != null) {
                    SettingsRow(
                        title = "Edit colour scheme",
                        chevron = true,
                        onClick = { overrideEditor = OverrideEditor.COLOUR_SCHEME },
                    )
                }

                val layoutName = keyBarLayouts.firstOrNull { it.id == keyBarLayoutId }?.name
                PickerRow(
                    title = "Key bar layout",
                    value = { DimText(layoutName ?: DEFAULT_KEY_BAR_LAYOUT) },
                ) { dismiss ->
                    DropdownMenuItem(
                        text = { Text(DEFAULT_KEY_BAR_LAYOUT) },
                        onClick = { keyBarLayoutId = null; dismiss() },
                    )
                    keyBarLayouts.forEach { layout ->
                        DropdownMenuItem(
                            text = { Text(layout.name) },
                            onClick = { keyBarLayoutId = layout.id; dismiss() },
                        )
                    }
                }
            }

            CollapsibleSection("Wake-on-LAN") {
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
            }

            if (existing != null) {
                CollapsibleSection("Port forwarding") {
                    TunnelsSection(
                        host = existing,
                        portForwardDao = portForwardDao,
                        sessionManager = sessionManager
                    )
                }
                CollapsibleSection("Key enrolment") {
                    KeyEnrolmentSection(
                        host = existing,
                        credentialVault = credentialVault,
                        hostDao = hostDao,
                        keyEnrolment = keyEnrolment,
                        activity = activity
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
}

private const val DEFAULT_KEY_BAR_LAYOUT = "Default (Esc, Tab, arrows)"

@Composable
private fun PickerRow(
    title: String,
    value: @Composable () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clickable(role = Role.DropdownList) { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                value()
            }
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu { expanded = false }
        }
    }
}

@Composable
private fun DimText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A nickname is a human label; the fallback user@host:port is machine-asserted identity. */
@Composable
private fun HostChoice(host: HostEntity) {
    val label = host.label
    if (label != null) {
        Text(label)
    } else {
        MachineText("${host.username}@${host.hostname}:${host.port}")
    }
}

private fun AuthKind.label(): String =
    when (this) {
        AuthKind.PASSWORD -> "Password"
        AuthKind.IMPORT_KEY -> "Import key"
        AuthKind.GENERATE_KEY -> "Generate key"
        AuthKind.KEYBOARD_INTERACTIVE -> "Keyboard-interactive"
    }
