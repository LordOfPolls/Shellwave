package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.GroupEntity
import io.github.lordofpolls.shellwave.terminal.parseHexColorOrNull
import io.github.lordofpolls.shellwave.terminal.toHexColorString
import android.graphics.Color as AndroidColor

/** [preview] draws the candidate colour in context, so the caller's own preview needn't stay on screen. */
@Composable
fun ColorPickerDialog(
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    preview: (@Composable (Int) -> Unit)? = null,
) {
    // HSV and not ARGB: at zero saturation ARGB has no hue, so the hue bar would jump to red.
    var hsv by rememberSaveable { mutableStateOf(FloatArray(3).also { AndroidColor.colorToHSV(initial, it) }) }
    // Typed hex wins over hsv while it parses: an HSV round trip can shift a channel by one, and a
    // colour typed exactly should come back exactly.
    var typedHex by rememberSaveable { mutableStateOf<String?>(null) }
    val typedInvalid = typedHex != null && parseHexColorOrNull(typedHex!!) == null
    val argb = typedHex?.let(::parseHexColorOrNull) ?: AndroidColor.HSVToColor(hsv)
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))

    fun setHsv(new: FloatArray) {
        hsv = new
        typedHex = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a colour") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                preview?.invoke(argb)
                FlowRow {
                    GroupEntity.SWATCHES.forEachIndexed { i, swatch ->
                        ColorSwatch(
                            argb = swatch,
                            description = "Preset colour ${i + 1}",
                            selected = swatch.toHexColorString() == argb.toHexColorString(),
                            onClick = {
                                setHsv(FloatArray(3).also { AndroidColor.colorToHSV(swatch, it) })
                            },
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        .dragOrTap { pos, size ->
                            setHsv(
                                floatArrayOf(
                                    hsv[0],
                                    (pos.x / size.width).coerceIn(0f, 1f),
                                    1f - (pos.y / size.height).coerceIn(0f, 1f),
                                ),
                            )
                        },
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                            ),
                        )
                        .dragOrTap { pos, size ->
                            setHsv(floatArrayOf((pos.x / size.width).coerceIn(0f, 1f) * 360f, hsv[1], hsv[2]))
                        },
                )
                val hexText = typedHex ?: argb.toHexColorString()
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        typedHex = text
                        parseHexColorOrNull(text)?.let { parsed ->
                            hsv = FloatArray(3).also { AndroidColor.colorToHSV(parsed, it) }
                        }
                    },
                    label = { Text("Hex") },
                    isError = parseHexColorOrNull(hexText) == null,
                    supportingText = { Text("#RRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(argb) }, enabled = !typedInvalid) { Text("Select") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Modifier.dragOrTap(onPoint: (Offset, IntSize) -> Unit): Modifier =
    pointerInput(Unit) { detectTapGestures { onPoint(it, size) } }
        .pointerInput(Unit) { detectDragGestures { change, _ -> onPoint(change.position, size) } }
