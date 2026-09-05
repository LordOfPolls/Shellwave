package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.billing.SupporterTier

/**
 * Asked once, after enough use to know whether the app is worth anything to this user, and never
 * again whichever button is pressed.
 *
 * Callers gate this on [io.github.lordofpolls.shellwave.core.billing.SupporterState.Purchasable],
 * so an F-Droid build and an existing supporter never reach it.
 */
@Composable
fun SupportPromptDialog(
    tiers: List<SupporterTier>,
    onBecomeSupporter: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("Enjoying Shellwave?") },
        text = {
            Column {
                Text(
                    "Thanks for using Shellwave. It's built by one person in their spare time and " +
                        "given away free. If you'd like to chip in, there's a one-time tip in a few " +
                        "sizes. Nothing is locked behind it.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiers.forEach { tier ->
                        TextButton(onClick = { onBecomeSupporter(tier.purchaseOptionId) }) {
                            Text(tier.priceLabel)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("No thanks") } },
    )
}
