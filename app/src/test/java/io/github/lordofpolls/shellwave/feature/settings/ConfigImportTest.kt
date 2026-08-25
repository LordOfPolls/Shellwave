package io.github.lordofpolls.shellwave.feature.settings

import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityInterval
import io.github.lordofpolls.shellwave.core.prefs.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConfigImportTest {
    private val credential =
        CredentialEntity(
            id = 3,
            type = "PASSWORD",
            label = "nas login",
            keystoreAlias = "vault_biometric_windowed",
            secretIv = byteArrayOf(1, 2, 3),
            secretCiphertext = "SEALED-SECRET-MARKER".toByteArray(),
            passphraseIv = null,
            passphraseCiphertext = null,
            publicKeyText = "ssh-ed25519 AAAAC3Nz nas",
            createdAt = 1000,
        )

    private val jumpHost =
        HostEntity(
            id = 1,
            label = "bastion",
            hostname = "bastion.example",
            port = 22,
            username = "polls",
            credentialId = 3,
            lastConnectedAt = null,
            createdAt = 1000,
        )

    private val host =
        jumpHost.copy(
            id = 2,
            label = "nas",
            hostname = "10.0.0.4",
            username = "root",
            lastConnectedAt = 2000,
            resilientSession = true,
            terminalProfileId = 7,
            colorSchemeId = 8,
            keyBarLayoutId = 9,
            proxyJumpHostId = 1,
            macAddress = "3c:22:fb:01:02:03",
        )

    private val forward =
        PortForwardEntity(
            id = 4,
            hostId = 2,
            type = "DYNAMIC",
            bindAddress = "127.0.0.1",
            bindPort = 1080,
            targetHost = null,
            targetPort = null,
            autoStart = true,
        )

    private val script =
        ScriptEntity(
            id = 5,
            name = "restart nginx",
            icon = "🚀",
            color = -16711936,
            targetHostId = 2,
            snippet = "sudo systemctl restart nginx",
            mode = "CAPTURE",
            disconnectAfter = true,
            paramsJson = "[]",
            confirmBeforeRun = true,
            createdAt = 1000,
            allowAutomation = true,
        )

    private val profile =
        TerminalProfileEntity(
            id = 7,
            name = "Default",
            fontFamily = "MONO",
            customFontUri = null,
            fontSizeSp = 13.5f,
            lineHeightMultiplier = 1.2f,
            cursorStyle = "BAR",
            cursorBlink = true,
            scrollbackLines = 4000,
        )

    private val scheme =
        ColorSchemeEntity(
            id = 8,
            name = "Solarized",
            isBuiltIn = false,
            background = -16777216,
            foreground = -1,
            cursor = -65536,
            selection = -8355712,
            ansiColorsCsv = "#000000,#ffffff",
        )

    private val layout = KeyBarLayoutEntity(id = 9, name = "Vim", keysJson = "[]", rows = 2)

    private fun export(
        hosts: List<HostEntity> = listOf(jumpHost, host),
        settings: Map<String, Any?> =
            mapOf(
                "themeMode" to "DARK",
                "dynamicColour" to false,
                "exactSchemeColours" to true,
                "bellMode" to "SILENT",
                "fullWidthTerminal" to true,
                "reachabilityEnabled" to true,
                "reachabilityInterval" to "FIVE_MINUTES",
                "reachabilityAllowsMetered" to false,
                "automationEnabled" to true,
                "widgetPinnedScriptIds" to listOf(5L),
                "quickSettingsTileScriptId" to 5L,
            ),
    ) = buildConfigExport(
        hosts = hosts,
        credentials = listOf(credential),
        portForwards = listOf(forward),
        scripts = listOf(script),
        terminalProfiles = listOf(profile),
        colorSchemes = listOf(scheme),
        keyBarLayouts = listOf(layout),
        settings = settings,
        exportedAt = Instant.parse("2026-08-25T10:00:00Z"),
    )

    @Test
    fun readsBackEveryRowTheExportWrote() {
        val parsed = parseConfigImport(export())

        assertEquals(listOf(jumpHost, host), parsed.hosts)
        assertEquals(listOf(forward), parsed.portForwards)
        // `icon` is the one column the export drops, so it cannot survive the round trip.
        assertEquals(listOf(script.copy(icon = null)), parsed.scripts)
        assertEquals(listOf(profile), parsed.terminalProfiles)
        assertEquals(listOf(scheme), parsed.colorSchemes)
        assertEquals(listOf(layout), parsed.keyBarLayouts)
    }

    @Test
    fun aCredentialComesBackWithoutItsSealedColumns() {
        val imported = parseConfigImport(export()).credentials.single()

        assertEquals(3L, imported.id)
        assertEquals("PASSWORD", imported.type)
        assertEquals("nas login", imported.label)
        assertEquals("ssh-ed25519 AAAAC3Nz nas", imported.publicKeyText)
        assertNull(imported.keystoreAlias)
        assertNull(imported.secretIv)
        assertNull(imported.secretCiphertext)
    }

    @Test
    fun readsSettingsButNeverTurnsAutomationOn() {
        val settings = parseConfigImport(export()).settings

        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(false, settings.dynamicColour)
        assertEquals(true, settings.exactSchemeColours)
        assertEquals(BellMode.SILENT, settings.bellMode)
        assertEquals(true, settings.fullWidthTerminal)
        assertEquals(ReachabilityInterval.FIVE_MINUTES, settings.reachabilityInterval)
        assertEquals(listOf(5L), settings.widgetPinnedScriptIds)
        assertEquals(5L, settings.quickSettingsTileScriptId)
        // Recorded so the summary can explain itself; ConfigImporter never applies it.
        assertEquals(true, settings.automationWasEnabled)
    }

    @Test
    fun anAbsentOrUnknownSettingLeavesTheDeviceAlone() {
        val settings =
            parseConfigImport(export(settings = mapOf("themeMode" to "MIDNIGHT"))).settings

        assertNull(settings.themeMode)
        assertNull(settings.bellMode)
        assertNull(settings.fullWidthTerminal)
        assertNull(settings.quickSettingsTileScriptId)
        assertTrue(settings.widgetPinnedScriptIds.isEmpty())
    }

    @Test
    fun refusesAnythingThatIsNotAnExport() {
        listOf("not json at all", "{}", """{"format":"something-else","version":1}""")
            .forEach { text ->
                val thrown =
                    assertThrows(IllegalArgumentException::class.java) { parseConfigImport(text) }
                assertTrue(text, thrown.message!!.isNotBlank())
            }
    }

    @Test
    fun refusesAFormatVersionThisBuildDoesNotKnow() {
        val newer =
            export().replace(
                "\"version\": $CONFIG_EXPORT_VERSION",
                "\"version\": ${CONFIG_EXPORT_VERSION + 1}",
            )

        val thrown =
            assertThrows(IllegalArgumentException::class.java) { parseConfigImport(newer) }
        assertTrue(thrown.message!!.contains("Update Shellwave"))
    }

    @Test
    fun summaryCountsReadAsProse() {
        val summary =
            ConfigImportSummary(hosts = 2, credentials = 1, keyBarLayouts = 3, notes = listOf("a note"))

        assertEquals(
            listOf("Imported 2 hosts, 1 credential, 3 key bar layouts.", "a note"),
            summary.lines(),
        )
        assertEquals(listOf("That file held nothing to import."), ConfigImportSummary().lines())
    }
}
