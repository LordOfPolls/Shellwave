package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * [argb] null is the "no colour" choice, drawn as an outline so it cannot be read as a near-black
 * swatch.
 *
 * The ring is transparent rather than absent when unselected, so picking a colour does not resize
 * the dot next to it.
 */
@Composable
fun ColorSwatch(argb: Int?, description: String, selected: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .size(32.dp)
                .clip(CircleShape)
                // selectable over clickable: exactly one colour is selected, so Role.RadioButton
                // plus `selected` is the right semantics. A contentDescription would announce the
                // colour but never whether it is the chosen one.
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .semantics { contentDescription = description }
                .then(
                    if (argb != null) {
                        Modifier.background(Color(argb))
                    } else {
                        Modifier.border(1.dp, outline, CircleShape)
                    },
                )
                .border(
                    2.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (argb == null) {
            Text(
                "∅",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
