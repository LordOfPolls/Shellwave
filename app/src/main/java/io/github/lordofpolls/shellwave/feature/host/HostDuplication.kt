package io.github.lordofpolls.shellwave.feature.host

import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.PortForwardDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.PortForwardEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity

/** Rows to insert so a duplicated host gets its own copy of everything it owns. */
data class DuplicatedHostAssets(
    val terminalProfile: TerminalProfileEntity?,
    val colorScheme: ColorSchemeEntity?,
    val portForwards: List<PortForwardEntity>,
)

fun duplicatedHostAssets(
    terminalProfile: TerminalProfileEntity?,
    colorScheme: ColorSchemeEntity?,
    portForwards: List<PortForwardEntity>,
    newHostId: Long,
): DuplicatedHostAssets = DuplicatedHostAssets(
    terminalProfile = terminalProfile?.copy(id = 0),
    colorScheme = colorScheme?.copy(id = 0),
    portForwards = portForwards.map { it.copy(id = 0, hostId = newHostId) },
)

// Override ids start null and are backfilled once their copied rows exist.
suspend fun duplicateHost(
    host: HostEntity,
    hostDao: HostDao,
    terminalProfileDao: TerminalProfileDao,
    colorSchemeDao: ColorSchemeDao,
    portForwardDao: PortForwardDao,
) {
    val newHost = host.copy(
        id = 0,
        label = "${host.label ?: host.hostname} (copy)",
        lastConnectedAt = null,
        createdAt = System.currentTimeMillis(),
        terminalProfileId = null,
        colorSchemeId = null,
    )
    val newHostId = hostDao.insert(newHost)
    val assets = duplicatedHostAssets(
        terminalProfile = host.terminalProfileId?.let { terminalProfileDao.getById(it) },
        colorScheme = host.colorSchemeId?.let { colorSchemeDao.getById(it) },
        portForwards = portForwardDao.getForHost(host.id),
        newHostId = newHostId,
    )
    val newTerminalProfileId = assets.terminalProfile?.let { terminalProfileDao.insert(it) }
    val newColorSchemeId = assets.colorScheme?.let { colorSchemeDao.insert(it) }
    if (newTerminalProfileId != null || newColorSchemeId != null) {
        hostDao.update(
            newHost.copy(
                id = newHostId,
                terminalProfileId = newTerminalProfileId,
                colorSchemeId = newColorSchemeId,
            ),
        )
    }
    assets.portForwards.forEach { portForwardDao.insert(it) }
}
