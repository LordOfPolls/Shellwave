package io.github.lordofpolls.shellwave.feature.settings

import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConfigExportTest {
    /** Marker bytes, not a real secret - but they are what [buildConfigExport] must not write out. */
    private val credential =
        CredentialEntity(
            id = 3,
            type = "PASSWORD",
            label = "nas login",
            keystoreAlias = "vault_biometric_windowed",
            secretIv = byteArrayOf(1, 2, 3),
            secretCiphertext = "SEALED-SECRET-MARKER".toByteArray(),
            passphraseIv = byteArrayOf(4, 5, 6),
            passphraseCiphertext = "SEALED-PASSPHRASE-MARKER".toByteArray(),
            publicKeyText = "ssh-ed25519 AAAAC3Nz nas",
            createdAt = 1000,
        )

    private val host =
        HostEntity(
            id = 1,
            label = "nas",
            hostname = "10.0.0.4",
            port = 22,
            username = "polls",
            credentialId = 3,
            lastConnectedAt = null,
            createdAt = 1000,
            macAddress = "3c:22:fb:01:02:03",
        )

    private fun export(
        hosts: List<HostEntity> = listOf(host),
        credentials: List<CredentialEntity> = listOf(credential),
        portForwards: List<PortForwardEntity> = emptyList(),
        scripts: List<ScriptEntity> = emptyList(),
        terminalProfiles: List<TerminalProfileEntity> = emptyList(),
        colorSchemes: List<ColorSchemeEntity> = emptyList(),
        keyBarLayouts: List<KeyBarLayoutEntity> = emptyList(),
        settings: Map<String, Any?> = mapOf("themeMode" to "DARK"),
    ) = buildConfigExport(
        hosts = hosts,
        credentials = credentials,
        portForwards = portForwards,
        scripts = scripts,
        terminalProfiles = terminalProfiles,
        colorSchemes = colorSchemes,
        keyBarLayouts = keyBarLayouts,
        settings = settings,
        exportedAt = Instant.parse("2026-08-25T10:00:00Z"),
    )

    @Test
    fun carriesNoSealedSecretFromTheCredentialTable() {
        val json = export()

        // Raw text, not the parsed tree: a secret under any key name at any depth still fails.
        assertFalse(json.contains("SEALED-SECRET-MARKER"))
        assertFalse(json.contains("SEALED-PASSPHRASE-MARKER"))
        assertFalse(json.contains("secretCiphertext"))
        assertFalse(json.contains("passphraseCiphertext"))
        assertFalse(json.contains("secretIv"))
        assertFalse(json.contains("passphraseIv"))
        assertFalse(json.contains("keystoreAlias"))

        val credentials = JSONObject(json).getJSONArray("credentials").getJSONObject(0)
        assertEquals(3L, credentials.getLong("id"))
        assertEquals("PASSWORD", credentials.getString("type"))
        assertEquals("nas login", credentials.getString("label"))
        assertEquals("ssh-ed25519 AAAAC3Nz nas", credentials.getString("publicKeyText"))
    }

    @Test
    fun writesHostsSettingsAndAHeader() {
        val parsed = JSONObject(export())

        assertEquals("shellwave-config", parsed.getString("format"))
        assertEquals(CONFIG_EXPORT_VERSION, parsed.getInt("version"))
        assertEquals("2026-08-25T10:00:00Z", parsed.getString("exportedAt"))
        assertEquals("DARK", parsed.getJSONObject("settings").getString("themeMode"))

        val exported = parsed.getJSONArray("hosts").getJSONObject(0)
        assertEquals("10.0.0.4", exported.getString("hostname"))
        assertEquals(22, exported.getInt("port"))
        assertEquals("3c:22:fb:01:02:03", exported.getString("macAddress"))
        assertEquals(3L, exported.getLong("credentialId"))
    }

    @Test
    fun aNullFieldStaysVisibleAsNull() {
        val parsed = JSONObject(export(hosts = listOf(host.copy(label = null))))
        val exported = parsed.getJSONArray("hosts").getJSONObject(0)

        assertTrue(exported.has("label"))
        assertTrue(exported.isNull("label"))
        assertTrue(exported.isNull("proxyJumpHostId"))
    }

    @Test
    fun everyCollectionIsPresentEvenWhenEmpty() {
        val parsed = JSONObject(export())

        listOf(
            "hosts",
            "credentials",
            "portForwards",
            "scripts",
            "terminalProfiles",
            "colourSchemes",
            "keyBarLayouts",
        ).forEach { key -> assertTrue(key, parsed.has(key)) }
        assertEquals(0, parsed.getJSONArray("scripts").length())
    }
}
