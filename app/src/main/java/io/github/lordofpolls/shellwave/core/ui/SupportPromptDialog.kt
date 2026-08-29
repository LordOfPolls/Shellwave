package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Asked once, after enough use to know whether the app is worth anything to this user, and never
 * again whichever button is pressed.
 *
 * Callers gate this on [io.github.lordofpolls.shellwave.core.billing.SupporterState.Purchasable],
 * so an F-Droid build and an existing supporter never reach it.
 */
@Composable
fun SupportPromptDialog(
    priceLabel: String,
    onBecomeSupporter: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("Enjoying Shellwave?") },
        text = {
            Text(
                "Thanks for using Shellwave. It's built by one person in their spare time and " +
                    "given away free. If you'd like to chip in, there's a one-time tip. " +
                    "Nothing is locked behind it.",
            )
        },
        confirmButton = { TextButton(onClick = onBecomeSupporter) { Text("Chip in · $priceLabel") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("No thanks") } },
    )
}
