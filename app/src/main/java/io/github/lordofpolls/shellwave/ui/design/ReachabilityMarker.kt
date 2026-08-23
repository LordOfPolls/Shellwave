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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.net.Reachability

/**
 * [Reachability.UNKNOWN] renders as an em dash, not the word "UNKNOWN". It is the state of having
 * no reading at all, and spelling that out at the same weight as `UP`/`DOWN` would make absence
 * look like a third measurement.
 */
fun reachabilityWord(reachability: Reachability): String =
    when (reachability) {
        Reachability.UP -> "UP"
        Reachability.DOWN -> "DOWN"
        Reachability.UNKNOWN -> "—"
    }

/** `UNKNOWN` is neutral: not knowing is not a problem. */
@Composable
fun reachabilityColor(reachability: Reachability): Color =
    when (reachability) {
        Reachability.UP -> StatusColors.ok()
        Reachability.DOWN -> StatusColors.crit()
        Reachability.UNKNOWN -> StatusColors.neutral()
    }

/**
 * Deliberately not an overload of StatusMarker. The two are different claims: LIVE means this app
 * has an open connection, UP means something answered on that port a moment ago and might not be
 * sshd. One component would invite one vocabulary, and then the two stop being distinguishable.
 *
 * `-` is not something a screen reader can say, so the row describes itself in words.
 */
@Composable
fun ReachabilityMarker(reachability: Reachability, modifier: Modifier = Modifier) {
    val color = reachabilityColor(reachability)
    Row(
        modifier = modifier.semantics {
            contentDescription = reachabilityDescription(reachability)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier
            .size(StatusSquareSize)
            .background(color)
            .clearAndSetSemantics {})
        MachineText(
            text = reachabilityWord(reachability),
            color = color,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * "Reachable" instead of the on-screen `UP`, which reads as jargon spoken aloud, and rather than
 * "online", which would overclaim: all the probe learned is that a TCP connect succeeded, which
 * says nothing about whether the machine is healthy.
 */
internal fun reachabilityDescription(reachability: Reachability): String =
    when (reachability) {
        Reachability.UP -> "Reachable"
        Reachability.DOWN -> "Not reachable"
        Reachability.UNKNOWN -> "Reachability unknown"
    }
