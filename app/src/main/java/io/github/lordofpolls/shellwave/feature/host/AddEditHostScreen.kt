package io.github.lordofpolls.shellwave.feature.host

import android.content.ClipData
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.CredentialType
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.CredentialDao
import io.github.lordofpolls.shellwave.core.db.dao.GroupDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.KeyBarLayoutDao
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.GroupEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.decodeAgentKeyIds
import io.github.lordofpolls.shellwave.core.db.entities.encodeAgentKeyIds
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.net.parseMacAddress
import io.github.lordofpolls.shellwave.feature.keys.credentialLabel
import io.github.lordofpolls.shellwave.feature.keys.keyDescription
import io.github.lordofpolls.shellwave.feature.home.nextGroupColor
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
import io.github.lordofpolls.shellwave.ssh.publicKeyComment
import io.github.lordofpolls.shellwave.ssh.publicKeyLineOf
import io.github.lordofpolls.shellwave.ssh.readKeyText
import io.github.lordofpolls.shellwave.ssh.resolveProxyHops
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.ui.design.BackTopBar
import io.github.lordofpolls.shellwave.ui.design.CollapsibleSection
import io.github.lordofpolls.shellwave.ui.design.ColorPickerDialog
import io.github.lordofpolls.shellwave.ui.design.ColorSwatch
import io.github.lordofpolls.shellwave.ui.design.LocalReadableInset
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.PickerRow
import io.github.lordofpolls.shellwave.ui.design.RadioRow
import io.github.lordofpolls.shellwave.ui.design.rememberFormState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AuthKind { PASSWORD, STORED_KEY, IMPORT_KEY, GENERATE_KEY, KEYBOARD_INTERACTIVE }

private enum class OverrideEditor(val title: String) {
    TERMINAL_PROFILE("Terminal profile"),
    COLOUR_SCHEME("Colour scheme"),
}

/**
 * Add or edit a host. Editing seals a fresh credential and repoints the host at it; the previous
 * credential row is left orphaned rather than chased down and deleted. [AuthKind.STORED_KEY] is the
 * exception: it points the host at a key from the Keys screen and seals nothing, and switching away
 * from it never touches that row.
 *
 * [originalAuthKind] seeds the radio selection from the stored CredentialType. Without it the
 * screen always opened on `AuthKind.PASSWORD`, which left Save disabled for any non-password host
 * until a secret was re-entered. A private key with a sealed secret shows as "Stored key", since
 * every such key is on the Keys screen; one without (a config import) shows as "Import key" so its
 * key can be entered.
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
    credentialDao: CredentialDao,
    keyEnrolment: KeyEnrolment,
    terminalProfileDao: TerminalProfileDao,
    colorSchemeDao: ColorSchemeDao,
    keyBarLayoutDao: KeyBarLayoutDao,
    groupDao: GroupDao,
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
    var agentForwarding by rememberFormState(existing?.id) { existing?.agentForwarding ?: false }
    var agentKeyIds by rememberFormState(existing?.id) {
        existing?.let { decodeAgentKeyIds(it.agentKeyIdsJson) } ?: emptyList()
    }
    val storedKeysOrNull by credentialDao.observeAll().collectAsState<List<CredentialEntity>, List<CredentialEntity>?>(initial = null)
    val storedKeys = storedKeysOrNull.orEmpty()
    var storedKeyId by rememberFormState<Long?>(existing?.id) { null }
    var detectingMac by remember { mutableStateOf(false) }
    // Not saved: the override rows it edits are entities that can't be, so a restored editor
    // would open empty.
    var overrideEditor by remember(existing?.id) { mutableStateOf<OverrideEditor?>(null) }

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
    var groupId by rememberFormState(existing?.id) { existing?.groupId }
    var color by rememberFormState(existing?.id) { existing?.color }
    var newGroupDialog by rememberFormState(existing?.id) { false }
    var customColorDialog by rememberFormState(existing?.id) { false }
    val groups by groupDao.observeAll().collectAsState(initial = emptyList())

    LaunchedEffect(existing?.id, existing?.terminalProfileId) {
        terminalProfileOverride =
            existing?.terminalProfileId?.let { terminalProfileDao.getById(it) }
    }
    LaunchedEffect(existing?.id, existing?.colorSchemeId) {
        colorSchemeOverride = existing?.colorSchemeId?.let { colorSchemeDao.getById(it) }
    }
    val keyBarLayouts by keyBarLayoutDao.observeAll().collectAsState(initial = emptyList())

    // Tracked rather than compared with the host's ids: an override row can be gone while the host
    // still points at it, which would read as an edit forever.
    var overridesToggled by rememberSaveable { mutableStateOf(false) }

    fun enableProfileOverride() {
        overridesToggled = true
        scope.launch {
            val fresh = DEFAULT_TERMINAL_PROFILE.copy(id = 0, name = "Host override")
            terminalProfileOverride = fresh.copy(id = terminalProfileDao.insert(fresh))
        }
    }

    fun disableProfileOverride() {
        val current = terminalProfileOverride ?: return
        overridesToggled = true
        terminalProfileOverride = null
        scope.launch { terminalProfileDao.delete(current) }
    }

    fun saveProfileOverride(updated: TerminalProfileEntity) {
        terminalProfileOverride = updated
        scope.launch { terminalProfileDao.update(updated) }
    }

    fun enableSchemeOverride() {
        overridesToggled = true
        scope.launch {
            val fresh = DEFAULT_COLOR_SCHEME.copy(id = 0, name = "Custom")
            colorSchemeOverride = fresh.copy(id = colorSchemeDao.insert(fresh))
        }
    }

    fun disableSchemeOverride() {
        val current = colorSchemeOverride ?: return
        overridesToggled = true
        colorSchemeOverride = null
        scope.launch { colorSchemeDao.delete(current) }
    }

    fun saveSchemeOverride(updated: ColorSchemeEntity) {
        colorSchemeOverride = updated
        scope.launch { colorSchemeDao.update(updated) }
    }

    var authKind by rememberSaveable { mutableStateOf(AuthKind.PASSWORD) }
    var originalAuthKind by rememberSaveable { mutableStateOf<AuthKind?>(null) }
    // A recreated screen keeps what the user picked rather than re-seeding it from the vault.
    var describedCredentialId by rememberSaveable { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var password by remember { mutableStateOf("") }

    var importedPem by remember { mutableStateOf("") }
    var importedPassphrase by remember { mutableStateOf("") }
    var parsedPublicKey by remember { mutableStateOf<String?>(null) }
    // The label only sets the comment, so it is applied here rather than re-parsing on every
    // keystroke in the Label field.
    val importedPublicKeyPreview = parsedPublicKey?.let { withPublicKeyComment(it, hostKeyLabel(label)) }
    var importedKeyError by remember { mutableStateOf<String?>(null) }

    fun parseImportedKey() =
        runCatching { publicKeyLineOf(importedPem, importedPassphrase.ifBlank { null }) }

    fun showParsedKey(result: Result<String>) {
        parsedPublicKey = result.getOrNull()
        importedKeyError = result.exceptionOrNull()?.let { it.message ?: "Could not parse key" }
    }

    // Save needs the parsed public line, so parse as the key is entered rather than behind a
    // button nobody knew was required. Debounced: an encrypted key's KDF is slow.
    LaunchedEffect(importedPem, importedPassphrase) {
        parsedPublicKey = null
        importedKeyError = null
        if (importedPem.isBlank()) return@LaunchedEffect
        delay(300)
        showParsedKey(withContext(Dispatchers.Default) { parseImportedKey() })
    }
    // Seeded from the stored credential's own certificate when editing (see the LaunchedEffect
    // below); certificateTouched tracks whether the user actually picked or cleared one this
    // session, so save() knows whether to write it back at all.
    var importedCertificate by rememberFormState<String?>(existing?.id) { null }
    var certificateTouched by rememberFormState(existing?.id) { false }
    var certificateError by remember { mutableStateOf<String?>(null) }
    var certificateNote by remember { mutableStateOf<String?>(null) }
    var certificateDropped by remember { mutableStateOf(false) }
    // The stored key's own public line, for the ed25519 certificate note when editing without
    // having re-typed the key (so importedPublicKeyPreview is still empty).
    var existingPublicKeyText by rememberFormState<String?>(existing?.id) { null }

    var generatedKey by remember { mutableStateOf<GeneratedKey?>(null) }
    var generateAlgorithm by rememberSaveable { mutableStateOf(GeneratedKeyAlgorithm.ED25519) }

    // A config import creates credentials with no sealed secret, and this is the screen that fills
    // one in. Assumed present until described, so an existing host never flashes the notice below.
    var storedSecretPresent by rememberFormState(existing?.id) { true }

    LaunchedEffect(existing?.credentialId) {
        val credentialId = existing?.credentialId ?: return@LaunchedEffect
        if (credentialId == describedCredentialId) return@LaunchedEffect
        val summary = credentialVault.describe(credentialId) ?: return@LaunchedEffect
        val kind =
            when (summary.type) {
                CredentialType.PASSWORD -> AuthKind.PASSWORD
                CredentialType.PRIVATE_KEY ->
                    if (summary.hasStoredSecret) AuthKind.STORED_KEY else AuthKind.IMPORT_KEY
                CredentialType.KEYBOARD_INTERACTIVE -> AuthKind.KEYBOARD_INTERACTIVE
            }
        if (kind == AuthKind.STORED_KEY) storedKeyId = credentialId
        authKind = kind
        originalAuthKind = kind
        storedSecretPresent = summary.hasStoredSecret
        requireBiometric = summary.requireBiometric
        importedCertificate = summary.certificate
        existingPublicKeyText = summary.publicKeyText
        describedCredentialId = credentialId
    }

    // Editing, radio still on the stored credential's auth kind, nothing typed that would replace
    // it - and something actually stored to keep.
    val keepExistingCredential =
        existing != null && authKind == originalAuthKind && storedSecretPresent &&
                when (authKind) {
                    AuthKind.PASSWORD -> password.isEmpty()
                    AuthKind.STORED_KEY -> false
                    AuthKind.IMPORT_KEY -> importedPem.isBlank()
                    AuthKind.GENERATE_KEY -> generatedKey == null
                    AuthKind.KEYBOARD_INTERACTIVE -> true
                }

    // The certificate state is shared by the import and stored-key forms, so a form switch or a
    // different stored key must not inherit what was picked for the other.
    fun resetCertificate(certificate: String?) {
        // Sticky: picking a stored key resets again with nothing touched, which must not hide the notice.
        if (certificateTouched && importedCertificate != null) certificateDropped = true
        importedCertificate = certificate
        certificateTouched = false
        certificateError = null
        certificateNote = null
    }

    // A rotated key must not silently carry over a certificate picked for the old one.
    fun setImportedPem(pem: String) {
        importedPem = pem
        certificateDropped = false
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
            val pickedFor = authKind
            val pickedKeyId = storedKeyId
            fun stale() = authKind != pickedFor || storedKeyId != pickedKeyId
            scope.launch {
                certificateNote = null
                certificateDropped = false
                val text = runCatching { readKeyText(activity.contentResolver, uri) }
                    .onFailure { certificateError = it.message ?: "Could not read that file" }
                    .getOrNull() ?: return@launch
                if (stale()) return@launch

                when {
                    // Load key and certificate together the way SshAuth will; sshj does not check
                    // they match, only that the pair loads. The standalone parse fallback exists
                    // because sshj drops a certificate on an ed25519 key (see loadKeysWithCertificate).
                    importedPem.isNotBlank() && authKind != AuthKind.STORED_KEY -> {
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
                    keepExistingCredential || (authKind == AuthKind.STORED_KEY && storedKeyId != null) -> {
                        val keyId = (if (pickedFor == AuthKind.STORED_KEY) pickedKeyId else existing?.credentialId)
                            ?: return@launch
                        val authMethod =
                            runCatching { credentialVault.resolve(keyId, activity) }
                                .getOrNull() as? AuthMethod.PrivateKey
                        if (stale()) return@launch
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

    val loginKeys = storedKeys.filter {
        it.type == CredentialType.PRIVATE_KEY.name && it.secretCiphertext != null
    }

    @Composable
    fun CertificateControls(publicKeyLine: String?) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { pickCertificate.launch(arrayOf("*/*")) }) {
                Text("Certificate (optional)")
            }
            if (importedCertificate != null) {
                TextButton(onClick = {
                    importedCertificate = null
                    certificateTouched = true
                    certificateError = null
                    certificateNote = null
                    certificateDropped = false
                }) {
                    Text("Clear")
                }
            }
        }
        certificateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        certificateNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (certificateDropped) {
            Text("Certificate cleared: it belonged to the previous key.", style = MaterialTheme.typography.bodySmall)
        }
        if (importedCertificate != null) {
            Text("Certificate loaded - offered instead of the bare key.", style = MaterialTheme.typography.bodySmall)
            // sshj 0.40.0 can't attach a certificate to an ed25519 openssh-key-v1 PEM
            // loaded from a string - see KeyImport.loadKeysWithCertificate's KDoc.
            if (publicKeyLine?.startsWith("ssh-ed25519 ") == true) {
                Text(
                    "ed25519 keys can't carry a certificate in this app yet (a known sshj limitation) - it will be ignored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    fun canSave(): Boolean =
        hostname.isNotBlank() && username.isNotBlank() && port.toIntOrNull() != null &&
                (keepExistingCredential ||
                        when (authKind) {
                            AuthKind.PASSWORD -> password.isNotEmpty()
                            AuthKind.STORED_KEY -> loginKeys.any { it.id == storedKeyId }
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
                val plan = planCredentialSave(
                    authKind = authKind,
                    storedKeyId = storedKeyId,
                    keepExistingCredential = keepExistingCredential,
                    certificateTouched = certificateTouched,
                    existingCredentialId = existing?.credentialId,
                ) ?: return@launch
                plan.certificateTargetId?.let { credentialVault.setCertificate(it, importedCertificate) }
                val credentialId =
                    when (val choice = plan.credential) {
                        is CredentialChoice.Existing -> choice.id
                        is CredentialChoice.Stored -> choice.id
                        CredentialChoice.SealNew ->
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
                                        hostKeyLabel(label),
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
                                        hostKeyLabel(label),
                                        requireBiometric,
                                        activity
                                    )
                                }

                                AuthKind.KEYBOARD_INTERACTIVE -> credentialVault.storeKeyboardInteractive(
                                    label.ifBlank { null })

                                AuthKind.STORED_KEY -> return@launch
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
                            groupId = groupId,
                            color = color,
                            agentForwarding = agentForwarding,
                            agentKeyIdsJson = encodeAgentKeyIds(agentKeyIds),
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
                            groupId = groupId,
                            position = if (existing.groupId != groupId) null else existing.position,
                            color = color,
                            agentForwarding = agentForwarding,
                            agentKeyIdsJson = encodeAgentKeyIds(agentKeyIds),
                        ),
                    )
                }
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Failed to save host"
            }
        }
    }

    if (newGroupDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newGroupDialog = false },
            title = { Text("New group") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val typed = name.trim()
                        scope.launch {
                            groupId = groupDao.insert(GroupEntity(name = typed, color = nextGroupColor(groups)))
                            newGroupDialog = false
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newGroupDialog = false }) { Text("Cancel") } },
        )
    }
    if (customColorDialog) {
        ColorPickerDialog(
            initial = color ?: GroupEntity.SWATCHES.first(),
            onPick = { color = it; customColorDialog = false },
            onDismiss = { customColorDialog = false },
        )
    }

    // The form leaves composition while an override editor is open; this keeps its sections'
    // expanded state and scroll position for the way back.
    val formStateHolder = rememberSaveableStateHolder()
    val editor = overrideEditor
    if (editor != null) {
        val appScheme by remember { colorSchemeDao.observeDefault() }.collectAsState(initial = null)
        BackHandler { overrideEditor = null }
        Column(modifier = modifier.fillMaxSize()) {
            BackTopBar(title = editor.title, onBack = { overrideEditor = null })
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LocalReadableInset.current)
                    .padding(16.dp),
            ) {
                when (editor) {
                    OverrideEditor.TERMINAL_PROFILE ->
                        terminalProfileOverride?.let {
                            TerminalProfileFields(
                                profile = it,
                                onChange = ::saveProfileOverride,
                                previewScheme = colorSchemeOverride ?: appScheme ?: DEFAULT_COLOR_SCHEME,
                            )
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

    fun hasUnsavedChanges(): Boolean =
        existing.formFields() != HostFormFields(
            label = label,
            hostname = hostname,
            port = port,
            username = username,
            groupId = groupId,
            color = color,
            resilientSession = resilientSession,
            macAddress = macAddress,
            agentForwarding = agentForwarding,
            agentKeyIds = agentKeyIds.toSet(),
            keyBarLayoutId = keyBarLayoutId,
            proxyJumpHostId = proxyJumpHostId,
        ) || overridesToggled || password.isNotEmpty() || importedPem.isNotBlank() || generatedKey != null ||
                certificateTouched || authKind != (originalAuthKind ?: AuthKind.PASSWORD) ||
                (authKind == AuthKind.STORED_KEY && storedKeyId != existing?.credentialId)

    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    fun leave() {
        if (hasUnsavedChanges()) confirmDiscard = true else onDone()
    }
    BackHandler { leave() }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to this host haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDone() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    formStateHolder.SaveableStateProvider("form") {
        Column(modifier = modifier.fillMaxSize()) {
            BackTopBar(
                title = if (existing == null) "Add host" else "Edit host",
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
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                val group = groups.firstOrNull { it.id == groupId }
                PickerRow(
                    title = "Group",
                    value = {
                        if (group == null) DimText("No group") else GroupChoice(group)
                    },
                ) { dismiss ->
                    DropdownMenuItem(
                        text = { Text("No group") },
                        onClick = { groupId = null; dismiss() },
                    )
                    groups.forEach { candidate ->
                        DropdownMenuItem(
                            text = { GroupChoice(candidate) },
                            onClick = { groupId = candidate.id; dismiss() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("+ New group") },
                        onClick = { newGroupDialog = true; dismiss() },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { hostname = it },
                        label = { Text("Hostname") },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false,
                            ),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.PasswordVisible,
                            autoCorrectEnabled = false,
                        ),
                    singleLine = true,
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
                        options = AuthKind.entries.filter {
                            it != AuthKind.STORED_KEY || storedKeysOrNull == null ||
                                    loginKeys.isNotEmpty() || authKind == AuthKind.STORED_KEY
                        },
                        selected = authKind,
                        labelOf = { it.label() },
                        onSelect = {
                            val leavingStoredKey = authKind == AuthKind.STORED_KEY
                            authKind = it
                            if (it == AuthKind.STORED_KEY) {
                                resetCertificate(loginKeys.firstOrNull { key -> key.id == storedKeyId }?.certificate)
                            } else if (leavingStoredKey) {
                                resetCertificate(null)
                            }
                        },
                    )
                }

                when (authKind) {
                    AuthKind.STORED_KEY -> {
                        if (storedKeysOrNull != null && loginKeys.isEmpty()) {
                            DimText(
                                if (storedKeys.any { it.type == CredentialType.PRIVATE_KEY.name }) {
                                    "No keys that can sign in yet. Pick Import key or Generate key instead."
                                } else {
                                    "No keys yet. Pick Import key or Generate key instead."
                                },
                            )
                        }
                        loginKeys.forEach { key ->
                            RadioRow(
                                selected = key.id == storedKeyId,
                                onClick = {
                                    if (storedKeyId != key.id) resetCertificate(key.certificate)
                                    storedKeyId = key.id
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { KeyChoice(key) }
                        }
                        loginKeys.firstOrNull { it.id == storedKeyId }?.let { key ->
                            CertificateControls(key.publicKeyText)
                            DimText("A certificate belongs to the key, so this applies to every host that uses it.")
                        }
                    }

                    AuthKind.PASSWORD ->
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            supportingText = if (existing != null && originalAuthKind == AuthKind.PASSWORD && storedSecretPresent) {
                                { Text("Leave blank to keep the saved password") }
                            } else null,
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
                            onValueChange = { setImportedPem(it) },
                            label = { Text("Or paste private key") },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                        OutlinedTextField(
                            value = importedPassphrase,
                            onValueChange = { importedPassphrase = it },
                            label = { Text("Passphrase (if encrypted)") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentType = ContentType.Password },
                        )
                        importedKeyError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { showParsedKey(parseImportedKey()) },
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

                        CertificateControls(importedPublicKeyPreview ?: existingPublicKeyText)
                    }

                    AuthKind.GENERATE_KEY -> {
                        PickerRow(
                            title = "Algorithm",
                            value = { DimText(generateAlgorithm.label) },
                        ) { dismiss ->
                            GeneratedKeyAlgorithm.entries.forEach { algorithm ->
                                DropdownMenuItem(
                                    text = { Text(algorithm.label) },
                                    onClick = { generateAlgorithm = algorithm; dismiss() },
                                )
                            }
                        }
                        Button(onClick = {
                            generatedKey = generateKeyPair(generateAlgorithm, hostKeyLabel(label))
                        }) { Text(if (generatedKey == null) "Generate key" else "Generate another") }
                        generatedKey?.let { key ->
                            Text(
                                "Next, add this public key to ~/.ssh/authorized_keys on the server, then save:",
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

                if (authKind != AuthKind.KEYBOARD_INTERACTIVE && authKind != AuthKind.STORED_KEY) {
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

                    SettingsSwitch(
                        title = "Agent forwarding",
                        description = "You confirm each key when you press Connect, and they stay forwarded " +
                                "until you close the tab. Anything on the host running as you, and root, can " +
                                "use them while you're connected.",
                        checked = agentForwarding,
                        onCheckedChange = { agentForwarding = it },
                    )
                    if (agentForwarding) {
                        val privateKeys = storedKeys.filter { it.type == CredentialType.PRIVATE_KEY.name }
                        Text("Keys to forward", style = MaterialTheme.typography.labelLarge)
                        privateKeys.forEach { key ->
                            val picked = key.id in agentKeyIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .toggleable(
                                        value = picked,
                                        role = Role.Checkbox,
                                        onValueChange = { agentKeyIds = if (it) agentKeyIds + key.id else agentKeyIds - key.id },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = picked, onCheckedChange = null)
                                KeyChoice(key, forwarded = true)
                            }
                        }
                        if (storedKeysOrNull != null) {
                            if (privateKeys.isEmpty()) {
                                DimText("No keys yet. Add one on the Keys tab; until then nothing will be forwarded.")
                            } else if (privateKeys.none { it.id in agentKeyIds }) {
                                DimText("No keys selected: nothing will be forwarded.")
                            }
                        }
                    }
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

                    Text("Colour", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorSwatch(
                            argb = null,
                            description = "No colour",
                            selected = color == null,
                            onClick = { color = null },
                        )
                        GroupEntity.SWATCHES.forEachIndexed { i, swatch ->
                            ColorSwatch(
                                argb = swatch,
                                description = SWATCH_NAMES.getOrElse(i) { "Colour ${i + 1}" },
                                selected = color == swatch,
                                onClick = { color = swatch },
                            )
                        }
                        color?.takeIf { it !in GroupEntity.SWATCHES }?.let { custom ->
                            ColorSwatch(
                                argb = custom,
                                description = "Custom colour",
                                selected = true,
                                onClick = { customColorDialog = true },
                            )
                        }
                        TextButton(onClick = { customColorDialog = true }) { Text("Custom…") }
                    }
                    DimText("Marks this host in the list. Blank follows its group's colour.")

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
                } else {
                    DimText("Port forwarding and key enrolment appear here once the host is saved.")
                }

                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = ::leave) { Text("Cancel") }
                    Button(onClick = ::save, enabled = canSave()) { Text("Save") }
                }
            }
        }
    }
}

private data class HostFormFields(
    val label: String,
    val hostname: String,
    val port: String,
    val username: String,
    val groupId: Long?,
    val color: Int?,
    val resilientSession: Boolean,
    val macAddress: String,
    val agentForwarding: Boolean,
    val agentKeyIds: Set<Long>,
    val keyBarLayoutId: Long?,
    val proxyJumpHostId: Long?,
)

private fun HostEntity?.formFields() = HostFormFields(
    label = this?.label.orEmpty(),
    hostname = this?.hostname.orEmpty(),
    port = (this?.port ?: 22).toString(),
    username = this?.username.orEmpty(),
    groupId = this?.groupId,
    color = this?.color,
    resilientSession = this?.resilientSession ?: false,
    macAddress = this?.macAddress.orEmpty(),
    agentForwarding = this?.agentForwarding ?: false,
    agentKeyIds = this?.let { decodeAgentKeyIds(it.agentKeyIdsJson).toSet() }.orEmpty(),
    keyBarLayoutId = this?.keyBarLayoutId,
    proxyJumpHostId = this?.proxyJumpHostId,
)

/** Swaps the comment, which may itself contain spaces, after an OpenSSH line's type and key. */
internal fun withPublicKeyComment(line: String, label: String?): String =
    line.split(' ', limit = 3).take(2).joinToString(" ") + " " + publicKeyComment(label)

internal fun hostKeyLabel(hostLabel: String?): String? =
    hostLabel?.takeIf { it.isNotBlank() }?.let { "$it key" }

@Composable
private fun KeyChoice(key: CredentialEntity, forwarded: Boolean = false) {
    Column(modifier = Modifier.padding(start = 8.dp)) {
        Text(credentialLabel(key), style = MaterialTheme.typography.titleSmall)
        Text(
            keyDescription(key, forwarded),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (key.certificate != null && !forwarded) {
            Text(
                "Accepted wherever its CA is trusted, not just here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** In [GroupEntity.SWATCHES] order, so TalkBack names a colour instead of reading an index. */
private val SWATCH_NAMES =
    listOf("Red", "Orange", "Amber", "Lime", "Green", "Teal", "Blue", "Indigo", "Purple", "Pink")

private const val DEFAULT_KEY_BAR_LAYOUT ="Default (Esc, Tab, arrows)"

@Composable
private fun DimText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GroupChoice(group: GroupEntity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(group.color)))
        Text(group.name)
    }
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
        AuthKind.STORED_KEY -> "Stored key"
        AuthKind.IMPORT_KEY -> "Import key"
        AuthKind.GENERATE_KEY -> "Generate key"
        AuthKind.KEYBOARD_INTERACTIVE -> "Keyboard-interactive"
    }
