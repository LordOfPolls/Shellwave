package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.SessionStatus

/**
 * `CONNECTED`/`DISCONNECTED` keep their names at the enum level, where they are load-bearing across
 * session state; the rename to `LIVE`/`CLOSED` happens once, here. No `else` branch, so a new
 * `SessionStatus` breaks the build instead of rendering a blank word.
 */
fun statusWord(status: SessionStatus): String =
    when (status) {
        SessionStatus.CONNECTING -> "CONNECTING"
        SessionStatus.CONNECTED -> "LIVE"
        SessionStatus.RECONNECTING -> "RECONNECTING"
        SessionStatus.DISCONNECTED -> "CLOSED"
        SessionStatus.FAILED -> "FAILED"
    }

/** Closed is not a problem, so `DISCONNECTED` is neutral. */
@Composable
fun statusColor(status: SessionStatus): Color =
    when (status) {
        SessionStatus.CONNECTED -> StatusColors.ok()
        SessionStatus.CONNECTING, SessionStatus.RECONNECTING -> StatusColors.warn()
        SessionStatus.FAILED -> StatusColors.crit()
        SessionStatus.DISCONNECTED -> StatusColors.neutral()
    }

/**
 * Square plus word, the same in host cards, session cards and notifications. Binding the two
 * together here keeps "colour alone" from being a per-call-site decision.
 *
 * `labelSmall` is passed explicitly: [MachineText] defaults `style` to `LocalTextStyle.current`,
 * which once rendered the status word at display scale on the Hosts screen. There is no `style`
 * parameter here for the same reason.
 */
@Composable
fun StatusMarker(status: SessionStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatusSquare(status)
        MachineText(
            text = statusWord(status),
            color = color,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * The square alone, for SessionChipRail, where a rail of chips each restating "LIVE" stops being
 * scannable. The caller must supply `stateDescription = statusWord(status)` on the chip node, since
 * colour reaches TalkBack not at all. That obligation is why this is a separate component and not a
 * `showWord: Boolean` on [StatusMarker], which would read as a styling choice.
 */
@Composable
fun StatusSquare(status: SessionStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Box(
        modifier =
            modifier
                .size(StatusSquareSize)
                .background(color)
                .clearAndSetSemantics {},
    )
}
