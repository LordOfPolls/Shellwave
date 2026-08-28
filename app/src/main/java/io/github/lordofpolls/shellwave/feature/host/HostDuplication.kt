package io.github.lordofpolls.shellwave.feature.host

import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
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
