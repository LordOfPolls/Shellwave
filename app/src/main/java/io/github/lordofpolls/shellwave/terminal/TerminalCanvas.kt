package io.github.lordofpolls.shellwave.terminal

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import kotlin.math.max
import kotlin.math.min

val DEFAULT_TERMINAL_TEXT_SIZE_SP = 14.sp

/**
 * Redraws without recomposing: [frame] is read during the draw phase, so bumping it elsewhere
 * scopes Compose's invalidation to this draw. The session screen coalesces those to ~16 ms.
 *
 * [topRow] is the external buffer row at the viewport's top (`<= 0`; 0 is the live screen bottom).
 * [onMeasured] fires with the new (cols, rows, cellWidthPx, cellHeightPx) whenever the pixel size
 * or font metrics change, undebounced.
 */
@Composable
fun TerminalCanvas(
    emulator: TerminalEmulator?,
    frame: IntState,
    onMeasured: (cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeSp: TextUnit = DEFAULT_TERMINAL_TEXT_SIZE_SP,
    typeface: Typeface = Typeface.MONOSPACE,
    lineHeightMultiplier: Float = 1f,
    topRow: Int = 0,
    selection: TerminalSelection? = null,
    selectionColor: Int = 0x662196F3,
) {
    val density = LocalDensity.current
    val renderer =
        remember(density, fontSizeSp, typeface, lineHeightMultiplier) {
            val textSizePx = with(density) { fontSizeSp.toPx() }.toInt()
            TerminalRenderer(textSizePx, typeface, lineHeightMultiplier)
        }

    // A font resize changes the cell grid without the layout box resizing, so cols/rows have to be
    // recomputed on a renderer change too, beyond onSizeChanged.
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(renderer, boxSize) {
        if (boxSize.width == 0 || boxSize.height == 0) return@LaunchedEffect
        val cellWidthPx = renderer.fontWidth.toInt().coerceAtLeast(1)
        val cellHeightPx = renderer.fontLineSpacing.coerceAtLeast(1)
        val cols = max(1, boxSize.width / cellWidthPx)
        val rows = max(1, boxSize.height / cellHeightPx)
        onMeasured(cols, rows, cellWidthPx, cellHeightPx)
    }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                // drawTerminal calls the raw Canvas's drawColor(), which paints the current clip
                // rect and not this composable's measured bounds; unclipped, the fill bleeds into
                // whatever is above it.
                .clipToBounds()
                .onSizeChanged { size -> boxSize = size },
    ) {
        drawTerminal(emulator, renderer, frame, topRow, selection, selectionColor)
    }
}

private fun DrawScope.drawTerminal(
    emulator: TerminalEmulator?,
    renderer: TerminalRenderer,
    frame: IntState,
    topRow: Int,
    selection: TerminalSelection?,
    selectionColor: Int,
) {
    @Suppress("UNUSED_EXPRESSION")
    frame.intValue

    val nativeCanvas = drawContext.canvas.nativeCanvas
    // TerminalRenderer paints a cell's background only when it differs from
    // palette[COLOR_INDEX_BACKGROUND], so ordinary cells rely entirely on this clear. Reading the
    // live palette instead of clearing to black gets a non-black scheme onto blank cells and the
    // area past the last row.
    val backgroundColor = emulator?.mColors?.mCurrentColors?.get(TextStyle.COLOR_INDEX_BACKGROUND)
        ?: android.graphics.Color.BLACK
    nativeCanvas.drawColor(backgroundColor)
    if (emulator != null) {
        renderer.render(emulator, nativeCanvas, topRow)
        if (selection != null) drawSelectionHighlight(
            emulator,
            renderer,
            topRow,
            selection.normalized(),
            selectionColor
        )
    }
}

/**
 * Drawn over whatever TerminalRenderer.render already painted, rather than threading a selection
 * rectangle through the vendored port's row loop. [highlightColor] comes from the caller because
 * the engine's colour model has no selection slot at all.
 */
private fun DrawScope.drawSelectionHighlight(
    emulator: TerminalEmulator,
    renderer: TerminalRenderer,
    topRow: Int,
    selection: TerminalSelection,
    highlightColor: Int
) {
    val cellWidth = renderer.fontWidth
    val cellHeight = renderer.fontLineSpacing.toFloat()
    val highlight = Color(highlightColor)
    val firstScreenRow = max(0, selection.startRow - topRow)
    val lastScreenRow = min(emulator.mRows - 1, selection.endRow - topRow)
    if (firstScreenRow > lastScreenRow) return
    for (screenRow in firstScreenRow..lastScreenRow) {
        val externalRow = screenRow + topRow
        val startCol = if (externalRow == selection.startRow) selection.startCol else 0
        val endColExclusive =
            if (externalRow == selection.endRow) selection.endCol + 1 else emulator.mColumns
        drawRect(
            color = highlight,
            topLeft = Offset(startCol * cellWidth, screenRow * cellHeight),
            size = Size((endColExclusive - startCol) * cellWidth, cellHeight),
        )
    }
}
