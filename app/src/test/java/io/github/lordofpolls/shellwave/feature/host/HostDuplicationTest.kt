package io.github.lordofpolls.shellwave.feature.host

import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of the duplicate-host deep copy; the DAO inserts it feeds need Room, so androidTest. */
class HostDuplicationTest {

    private val profile = TerminalProfileEntity(
        id = 7L,
        name = "profile",
        fontFamily = "MONO",
        fontSizeSp = 14f,
        lineHeightMultiplier = 1.1f,
        cursorStyle = "BLOCK",
        cursorBlink = true,
        scrollbackLines = 1000,
    )

    private val scheme = ColorSchemeEntity(
        id = 9L,
        name = "scheme",
        isBuiltIn = false,
        background = 0,
        foreground = 1,
        cursor = 2,
        selection = 3,
        ansiColorsCsv = "0,1,2",
    )

    private fun forward(id: Long, hostId: Long) = PortForwardEntity(
        id = id,
        hostId = hostId,
        type = "LOCAL",
        bindAddress = "127.0.0.1",
        bindPort = 8080,
        targetHost = "localhost",
        targetPort = 80,
        autoStart = true,
    )

    @Test
    fun `terminal profile and colour scheme are copied as new unsaved rows with the same settings`() {
        val assets = duplicatedHostAssets(
            terminalProfile = profile,
            colorScheme = scheme,
            portForwards = emptyList(),
            newHostId = 42L,
        )

        assertEquals(0L, assets.terminalProfile!!.id)
        assertEquals(profile.copy(id = 0), assets.terminalProfile)
        assertEquals(0L, assets.colorScheme!!.id)
        assertEquals(scheme.copy(id = 0), assets.colorScheme)
    }

    @Test
    fun `port forwards are copied as new rows pointing at the new host`() {
        val assets = duplicatedHostAssets(
            terminalProfile = null,
            colorScheme = null,
            portForwards = listOf(forward(id = 3L, hostId = 1L), forward(id = 4L, hostId = 1L)),
            newHostId = 42L,
        )

        assertEquals(2, assets.portForwards.size)
        assertTrue(assets.portForwards.all { it.id == 0L })
        assertTrue(assets.portForwards.all { it.hostId == 42L })
        assertEquals(8080, assets.portForwards[0].bindPort)
    }

    @Test
    fun `a host with no override rows produces no override copies`() {
        val assets = duplicatedHostAssets(
            terminalProfile = null,
            colorScheme = null,
            portForwards = emptyList(),
            newHostId = 42L,
        )

        assertNull(assets.terminalProfile)
        assertNull(assets.colorScheme)
        assertTrue(assets.portForwards.isEmpty())
    }
}
