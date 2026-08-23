package io.github.lordofpolls.shellwave.ui.design

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val FINGERPRINT_GROUP_SIZE = 8

/**
 * Grouped for display, verbatim on copy, and that split is the point. An unbroken 43-character
 * base64 run is the shape an eye skips over, but a copied string has to be byte-identical or
 * pasting it into a comparison fails quietly, so [onCopy] gets [fingerprint] untouched.
 *
 * `SHA256:<base64>` is OpenSSH's format. sshj's MD5 hex would match nothing the user can compare
 * against. The prefix sits on its own line; inline it pushes the first group out of alignment with
 * the ones below.
 */
@Composable
fun FingerprintBlock(fingerprint: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val prefix = fingerprint.substringBefore(':', missingDelimiterValue = "")
                if (prefix.isNotEmpty()) {
                    MachineText(
                        "$prefix:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MachineText(
                    groupFingerprint(fingerprint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText(
                                    "host key fingerprint",
                                    fingerprint
                                )
                            )
                        )
                    }
                },
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy fingerprint")
            }
        }
    }
}

/**
 * This only ever inserts separators. A regression that dropped or reordered characters would show a
 * wrong fingerprint that still looked plausible, which is the one failure a fingerprint display
 * must not have.
 */
fun groupFingerprint(fingerprint: String): String {
    val body = fingerprint.substringAfter(':', missingDelimiterValue = fingerprint)
    return body.chunked(FINGERPRINT_GROUP_SIZE).joinToString(" ")
}
