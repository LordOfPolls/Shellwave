package io.github.lordofpolls.shellwave.feature.host

import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDuplicationHostDao(private val hosts: MutableMap<Long, HostEntity>) : HostDao {
    var updated: HostEntity? = null
        private set
    private var nextId = 100L

    override fun observeAll(): Flow<List<HostEntity>> = TODO()
    override fun observeRecents(limit: Int): Flow<List<HostEntity>> = TODO()
    override suspend fun getById(id: Long): HostEntity? = hosts[id]
    override suspend fun insert(host: HostEntity): Long {
        val id = nextId++
        hosts[id] = host.copy(id = id)
        return id
    }
    override suspend fun update(host: HostEntity) {
        updated = host
        hosts[host.id] = host
    }
    override suspend fun delete(host: HostEntity) = TODO()
    override suspend fun getProxyJumpDependents(hostId: Long): List<HostEntity> = TODO()
    override suspend fun countOtherHostsUsingCredential(credentialId: Long, excludeHostId: Long): Int = TODO()
    override suspend fun touchLastConnected(id: Long, timestamp: Long) = TODO()
}

private class FakeTerminalProfileDao(private val profiles: Map<Long, TerminalProfileEntity>) : TerminalProfileDao {
    var inserted: TerminalProfileEntity? = null
        private set

    override suspend fun getAll(): List<TerminalProfileEntity> = TODO()
    override suspend fun getDefault(): TerminalProfileEntity? = TODO()
    override fun observeDefault(): Flow<TerminalProfileEntity?> = TODO()
    override suspend fun getById(id: Long): TerminalProfileEntity? = profiles[id]
    override suspend fun insert(profile: TerminalProfileEntity): Long {
        inserted = profile
        return 200L
    }
    override suspend fun update(profile: TerminalProfileEntity) = TODO()
    override suspend fun delete(profile: TerminalProfileEntity) = TODO()
}

private class FakeColorSchemeDao(private val schemes: Map<Long, ColorSchemeEntity>) : ColorSchemeDao {
    var inserted: ColorSchemeEntity? = null
        private set

    override suspend fun getAll(): List<ColorSchemeEntity> = TODO()
    override suspend fun getDefault(): ColorSchemeEntity? = TODO()
    override fun observeDefault(): Flow<ColorSchemeEntity?> = TODO()
    override suspend fun getById(id: Long): ColorSchemeEntity? = schemes[id]
    override suspend fun insert(scheme: ColorSchemeEntity): Long {
        inserted = scheme
        return 300L
    }
    override suspend fun update(scheme: ColorSchemeEntity) = TODO()
    override suspend fun delete(scheme: ColorSchemeEntity) = TODO()
}

private class FakePortForwardDao(private val forwards: Map<Long, List<PortForwardEntity>>) : PortForwardDao {
    val inserted = mutableListOf<PortForwardEntity>()

    override suspend fun getForHost(hostId: Long): List<PortForwardEntity> = forwards[hostId].orEmpty()
    override fun observeForHost(hostId: Long): Flow<List<PortForwardEntity>> = TODO()
    override suspend fun insert(forward: PortForwardEntity): Long {
        inserted += forward
        return forward.id
    }
    override suspend fun update(forward: PortForwardEntity) = TODO()
    override suspend fun delete(forward: PortForwardEntity) = TODO()
}

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

    private fun host(id: Long) = HostEntity(
        id = id,
        label = "original",
        hostname = "10.0.0.$id",
        port = 22,
        username = "user",
        credentialId = 1L,
        lastConnectedAt = 123L,
        createdAt = 0L,
        terminalProfileId = 7L,
        colorSchemeId = 9L,
    )

    @Test
    fun `duplicating a host backfills the new host with its copied overrides and forwards`() = runBlocking {
        val original = host(1)
        val hostDao = FakeDuplicationHostDao(mutableMapOf(1L to original))
        val terminalProfileDao = FakeTerminalProfileDao(mapOf(7L to profile))
        val colorSchemeDao = FakeColorSchemeDao(mapOf(9L to scheme))
        val portForwardDao = FakePortForwardDao(mapOf(1L to listOf(forward(id = 3L, hostId = 1L))))

        duplicateHost(original, hostDao, terminalProfileDao, colorSchemeDao, portForwardDao)

        val updated = hostDao.updated
        assertEquals(100L, updated?.id)
        assertEquals(200L, updated?.terminalProfileId)
        assertEquals(300L, updated?.colorSchemeId)
        assertEquals(1, portForwardDao.inserted.size)
        assertEquals(100L, portForwardDao.inserted[0].hostId)
    }
}
