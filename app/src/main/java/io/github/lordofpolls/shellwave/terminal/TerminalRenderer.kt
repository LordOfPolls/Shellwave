/*
 * Ported from termux-app's `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java`
 * at commit 3df69d1da197dd9bd71a3bafd902dffd720576b4 (https://github.com/termux/termux-app).
 * Upstream licence: GPLv3 only. See /NOTICE and terminal-core/VENDORING.md.
 *
 * Changes from upstream:
 *  - Ported from Java to Kotlin, field/getter access adjusted accordingly.
 *  - Selection-rectangle highlighting removed: `render` has no selection parameters, and
 *    `drawTextRun`'s reverse-video logic keeps cursor inversion but drops the `insideSelection`
 *    input upstream ORs in. `TerminalCanvas.kt` draws the selection highlight as a translucent
 *    overlay instead: it needs cell rectangles rather than glyph runs, so it does not belong here.
 *  - `topRow` shifts which buffer row is read for each screen row. The cursor is only drawn when
 *    `topRow == 0`: like every terminal emulator, there is no cursor to show in scrollback.
 *  - Invoked from a Compose `Canvas` via `drawContext.canvas.nativeCanvas` instead of a
 *    View's `onDraw(Canvas)`.
 */
package io.github.lordofpolls.shellwave.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Saves font metrics, so it must be recreated when the typeface, font size or
 * [lineHeightMultiplier] changes. One [Paint] is reused across frames and rows; never allocate one
 * per draw call.
 *
 * Cell width always comes from a single reference-glyph measurement ([fontWidth]) applied
 * uniformly, never from a per-glyph advance, because fonts that claim to be monospace occasionally
 * lie. [render]'s `fontWidthMismatch` handling scales a run to fit its column width when a glyph
 * doesn't measure up, but no individual glyph ever redefines the grid.
 */
class TerminalRenderer(textSize: Int, typeface: Typeface, lineHeightMultiplier: Float = 1f) {

    private val textPaint =
        Paint().apply {
            this.typeface = typeface
            isAntiAlias = true
            this.textSize = textSize.toFloat()
        }

    val fontWidth: Float = textPaint.measureText("X")

    val fontLineSpacing: Int = ceil(textPaint.fontSpacing.toDouble() * lineHeightMultiplier).toInt()

    private val fontAscent: Int = ceil(textPaint.ascent().toDouble()).toInt()

    val fontLineSpacingAndAscent: Int = fontLineSpacing + fontAscent

    private val asciiMeasures =
        FloatArray(127).also { measures ->
            val sb = StringBuilder(" ")
            for (i in measures.indices) {
                sb.setCharAt(0, i.toChar())
                measures[i] = textPaint.measureText(sb, 0, 1)
            }
        }

    /** [topRow] is the external buffer row (`<= 0`; 0 is the live screen) at the viewport's top. */
    fun render(emulator: TerminalEmulator, canvas: Canvas, topRow: Int = 0) {
        val reverseVideo = emulator.isReverseVideo
        val rows = emulator.mRows
        val columns = emulator.mColumns
        val cursorCol = emulator.cursorCol
        val cursorRow = emulator.cursorRow
        val screen = emulator.screen
        val top = topRow.coerceAtLeast(-screen.activeTranscriptRows)
        val cursorVisible = emulator.shouldCursorBeVisible() && top == 0
        val palette = emulator.mColors.mCurrentColors
        val cursorShape = emulator.cursorStyle

        if (reverseVideo) {
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC)
        }

        var heightOffset = fontLineSpacingAndAscent.toFloat()
        for (row in 0 until rows) {
            heightOffset += fontLineSpacing

            val cursorX = if (row == cursorRow && cursorVisible) cursorCol else -1

            val lineObject =
                screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row + top))
            val line = lineObject.mText
            val charsUsedInLine = lineObject.spaceUsed

            var lastRunStyle = 0L
            var lastRunInsideCursor = false
            var lastRunStartColumn = -1
            var lastRunStartIndex = 0
            var lastRunFontWidthMismatch = false
            var currentCharIndex = 0
            var measuredWidthForRun = 0f

            var column = 0
            while (column < columns) {
                val charAtIndex = line[currentCharIndex]
                val charIsHighSurrogate = Character.isHighSurrogate(charAtIndex)
                val charsForCodePoint = if (charIsHighSurrogate) 2 else 1
                val codePoint =
                    if (charIsHighSurrogate) {
                        Character.toCodePoint(charAtIndex, line[currentCharIndex + 1])
                    } else {
                        charAtIndex.code
                    }
                val codePointWcWidth = WcWidth.width(codePoint)
                val insideCursor =
                    cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1)
                val style = lineObject.getStyle(column)

                // Some fonts aren't truly monospace (emoji, exotic glyphs): detect a mismatch
                // between the measured width and what wcwidth() expects, and scale to match.
                val measuredCodePointWidth =
                    if (codePoint < asciiMeasures.size) {
                        asciiMeasures[codePoint]
                    } else {
                        textPaint.measureText(line, currentCharIndex, charsForCodePoint)
                    }
                val fontWidthMismatch =
                    abs(measuredCodePointWidth / fontWidth - codePointWcWidth) > 0.01

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column != 0) {
                        val columnWidthSinceLastRun = column - lastRunStartColumn
                        val charsSinceLastRun = currentCharIndex - lastRunStartIndex
                        val cursorColor =
                            if (lastRunInsideCursor) palette[TextStyle.COLOR_INDEX_CURSOR] else 0
                        val invertCursorTextColor =
                            lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
                        drawTextRun(
                            canvas,
                            line,
                            palette,
                            heightOffset,
                            lastRunStartColumn,
                            columnWidthSinceLastRun,
                            lastRunStartIndex,
                            charsSinceLastRun,
                            measuredWidthForRun,
                            cursorColor,
                            cursorShape,
                            lastRunStyle,
                            reverseVideo || invertCursorTextColor,
                        )
                    }
                    measuredWidthForRun = 0f
                    lastRunStyle = style
                    lastRunInsideCursor = insideCursor
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    lastRunFontWidthMismatch = fontWidthMismatch
                }
                measuredWidthForRun += measuredCodePointWidth
                column += codePointWcWidth
                currentCharIndex += charsForCodePoint
                while (currentCharIndex < charsUsedInLine && WcWidth.width(
                        line,
                        currentCharIndex
                    ) <= 0
                ) {
                    // Eat combining chars so they're treated as part of the last non-combining code
                    // point, instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += if (Character.isHighSurrogate(line[currentCharIndex])) 2 else 1
                }
            }

            val columnWidthSinceLastRun = columns - lastRunStartColumn
            val charsSinceLastRun = currentCharIndex - lastRunStartIndex
            val cursorColor = if (lastRunInsideCursor) palette[TextStyle.COLOR_INDEX_CURSOR] else 0
            val invertCursorTextColor =
                lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
            drawTextRun(
                canvas,
                line,
                palette,
                heightOffset,
                lastRunStartColumn,
                columnWidthSinceLastRun,
                lastRunStartIndex,
                charsSinceLastRun,
                measuredWidthForRun,
                cursorColor,
                cursorShape,
                lastRunStyle,
                reverseVideo || invertCursorTextColor,
            )
        }
    }

    private fun drawTextRun(
        canvas: Canvas,
        text: CharArray,
        palette: IntArray,
        y: Float,
        startColumn: Int,
        runWidthColumns: Int,
        startCharIndex: Int,
        runWidthChars: Int,
        measuredWidth: Float,
        cursor: Int,
        cursorStyle: Int,
        textStyle: Long,
        reverseVideo: Boolean,
    ) {
        var mes = measuredWidth
        var foreColor = TextStyle.decodeForeColor(textStyle)
        val effect = TextStyle.decodeEffect(textStyle)
        var backColor = TextStyle.decodeBackColor(textStyle)
        val bold =
            (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0
        val underline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
        val italic = (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0
        val strikeThrough = (effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0
        val dim = (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0

        if ((foreColor and -0x1000000) != -0x1000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor in 0..7) foreColor += 8
            foreColor = palette[foreColor]
        }

        if ((backColor and -0x1000000) != -0x1000000) {
            backColor = palette[backColor]
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        val reverseVideoHere =
            reverseVideo xor ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)
        if (reverseVideoHere) {
            val tmp = foreColor
            foreColor = backColor
            backColor = tmp
        }

        var left = startColumn * fontWidth
        var right = left + runWidthColumns * fontWidth

        mes /= fontWidth
        var savedMatrix = false
        if (abs(mes - runWidthColumns) > 0.01) {
            canvas.save()
            canvas.scale(runWidthColumns / mes, 1f)
            left *= mes / runWidthColumns
            right *= mes / runWidthColumns
            savedMatrix = true
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            textPaint.color = backColor
            canvas.drawRect(left, y - fontLineSpacingAndAscent + fontAscent, right, y, textPaint)
        }

        if (cursor != 0) {
            textPaint.color = cursor
            var cursorHeight = fontLineSpacingAndAscent - fontAscent
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cursorHeight /= 4
            } else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                right -= (right - left) * 3 / 4
            }
            canvas.drawRect(left, y - cursorHeight, right, y, textPaint)
        }

        if ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                var red = 0xFF and (foreColor shr 16)
                var green = 0xFF and (foreColor shr 8)
                var blue = 0xFF and foreColor
                // Dim color handling used by libvte, in turn taken from xterm:
                red = red * 2 / 3
                green = green * 2 / 3
                blue = blue * 2 / 3
                foreColor = -0x1000000 + (red shl 16) + (green shl 8) + blue
            }

            textPaint.isFakeBoldText = bold
            textPaint.isUnderlineText = underline
            textPaint.textSkewX = if (italic) -0.35f else 0f
            textPaint.isStrikeThruText = strikeThrough
            textPaint.color = foreColor

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(
                text,
                startCharIndex,
                runWidthChars,
                startCharIndex,
                runWidthChars,
                left,
                y - fontLineSpacingAndAscent,
                false,
                textPaint
            )
        }

        if (savedMatrix) canvas.restore()
    }
}
