package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AgentMarker(modifier: Modifier = Modifier) {
    MachineText(
        text = "AGENT",
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
    )
}
