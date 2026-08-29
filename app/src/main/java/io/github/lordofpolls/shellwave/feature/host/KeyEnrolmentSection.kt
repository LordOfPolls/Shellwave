package io.github.lordofpolls.shellwave.feature.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.ssh.GeneratedKey
import io.github.lordofpolls.shellwave.ssh.GeneratedKeyAlgorithm
import io.github.lordofpolls.shellwave.ssh.KeyEnrolment
import io.github.lordofpolls.shellwave.ssh.generateKeyPair
import io.github.lordofpolls.shellwave.ssh.resolveProxyHops
import kotlinx.coroutines.launch

/**
 * Only shown for an already-saved host: enrolment needs an already-authenticated session to install
 * into, which a not-yet-saved host doesn't have.
 *
 * Two separate button presses and not one combined action. Generating a key and pushing it is
 * idempotent and safe to redo; switching the saved host over to authenticate with it is a real
 * change to how the host is reached next time. Someone who only wanted the key on the server should
 * not be forced to repoint the saved host too.
 */
@Composable
fun KeyEnrolmentSection(
    host: HostEntity,
    credentialVault: CredentialVault,
    hostDao: HostDao,
    keyEnrolment: KeyEnrolment,
    activity: FragmentActivity,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pushedKey by remember { mutableStateOf<GeneratedKey?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var switched by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Generates a key on this device, installs it in this host's authorized_keys, and replaces " +
                    "its saved credential.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    result = null
                    switched = false
                    scope.launch {
                        try {
                            val key = generateKeyPair(GeneratedKeyAlgorithm.ED25519)
                            val authMethod = credentialVault.resolve(host.credentialId, activity)
                            val hops = resolveProxyHops(host, hostDao, credentialVault, activity)
                            val outcome =
                                keyEnrolment.enroll(
                                    "Key enrolment: ${host.username}@${host.hostname}",
                                    host.hostname,
                                    host.port,
                                    host.username,
                                    authMethod,
                                    key.publicKeyLine,
                                    hops,
                                )
                            result = outcome.message
                            if (outcome.success) pushedKey = key
                        } catch (e: Exception) {
                            result = e.message ?: "Key enrolment failed"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Push new key to host") }

            if (pushedKey != null && !switched) {
                Button(
                    enabled = !busy,
                    onClick = {
                        val key = pushedKey ?: return@Button
                        busy = true
                        scope.launch {
                            try {
                                val credentialId = credentialVault.storePrivateKey(
                                    key.privateKeyPem,
                                    null,
                                    key.publicKeyLine,
                                    host.label,
                                    requireBiometric = false,
                                    activity
                                )
                                hostDao.update(host.copy(credentialId = credentialId))
                                switched = true
                                result = "Key installed and this host now signs in with it."
                            } catch (e: Exception) {
                                result = e.message ?: "Could not switch this host to the new key"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Use this key for this host") }
            }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
