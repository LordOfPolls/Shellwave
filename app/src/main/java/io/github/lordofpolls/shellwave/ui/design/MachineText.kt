package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * Machine truth: hostnames, `user@host:port`, fingerprints, ports, key types, byte counts, exit
 * statuses, status words. Anything a human is being told (titles, labels, buttons, dialog prose)
 * stays plain M3 [Text]. Routing every identity- or status-bearing string through one component is
 * what makes a raw `Text(` on those paths greppable.
 *
 * Two things override whatever [style] is merged in: `ChromeMonoFontFamily`, never the user's
 * terminal font, and tabular figures, so a column of fingerprint bytes lines up.
 */
@Composable
fun MachineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
) {
    val resolvedColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    Text(
        text = text,
        modifier = modifier,
        color = resolvedColor,
        textAlign = textAlign,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        style = style.merge(fontFamily = ChromeMonoFontFamily, fontFeatureSettings = "tnum"),
    )
}
