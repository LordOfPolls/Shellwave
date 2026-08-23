package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

private const val WORDMARK_PREFIX = "~/"
private const val WORDMARK_SPOKEN = "Shellwave"

/**
 * `~/shellwave`, where the tilde is both home and a wave.
 *
 * Mono but not `MachineText`: that marks things a server asserts, and a brand name is the app
 * talking about itself. Only the prefix takes `colorScheme.primary`, so the wordmark picks up
 * Material You while the launcher icon stays a fixed brand constant. TalkBack gets "Shellwave", not
 * the drawn string, which would announce as "tilde slash shellwave".
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Text(
        text =
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.primary)) { append(WORDMARK_PREFIX) }
                withStyle(SpanStyle(color = colors.onSurface)) { append(WORDMARK_SPOKEN.lowercase()) }
            },
        modifier = modifier.clearAndSetSemantics { contentDescription = WORDMARK_SPOKEN },
        style =
            MaterialTheme.typography.titleLarge.merge(
                fontFamily = ChromeMonoFontFamily,
            ),
    )
}
