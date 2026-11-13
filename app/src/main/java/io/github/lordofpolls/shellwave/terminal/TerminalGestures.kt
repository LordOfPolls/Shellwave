package io.github.lordofpolls.shellwave.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.termux.terminal.TerminalEmulator
import kotlin.math.abs
import kotlin.math.hypot

private const val LONG_PRESS_TIMEOUT_MS = 500L

/** Only needs to tell a stationary long-press from a drag. */
private const val TOUCH_SLOP_PX = 24f

/**
 * A physical mouse is exact, so far less travel than [TOUCH_SLOP_PX] separates a click from a drag.
 * Also the radius within which a second click counts as a double-click.
 */
private const val MOUSE_DRAG_SLOP_PX = 8f

private const val WHEEL_ROWS_PER_NOTCH = 3

private enum class GestureMode { UNDECIDED, DRAG, PINCH, LONG_PRESS }

/**
 * One finger dragging scrolls the scrollback or forwards mouse events; a long press without
 * movement starts selection; a second finger turns the gesture into a font-resizing pinch; a tap
 * focuses the IME or forwards a click. Which of each pair happens is the caller's decision.
 *
 * Hand-rolled instead of layering `detectDragGestures`, `detectTransformGestures` and
 * `detectTapGestures` in separate `pointerInput` blocks. Each of those consumes the same one-finger
 * drag, since it reads as a pan to the transform detector too, so telling scrolling from pinching
 * needs one source of truth for how many fingers are down.
 *
 * A physical mouse takes its own path ahead of that machine: a primary press-drag selects, a
 * double-click selects the word, a right-click copies or pastes, and the wheel scrolls. There is
 * no long-press to select, since a held button is how a mouse starts a drag. While the remote app
 * has mouse tracking on, a mouse press is forwarded to it as touch is, unless Shift is held, which
 * bypasses tracking for local selection as xterm does.
 *
 * Selection handles need no special case: each lives in its own `Popup`, a separate Android window,
 * so the platform routes a finger on one straight there and this never sees it.
 *
 * The initial down is claimed on `PointerEventPass.Initial` rather than the default `Main` pass,
 * which runs children first. Without that, [TerminalInputCapture]'s invisible field won every
 * long-press and raised its own "Paste / Select all / Autofill" menu. `NoOpTextToolbar` suppresses
 * that field's toolbar, not the gesture behind it; consuming the down first stops its
 * `awaitFirstDown()` from claiming the touch.
 */
fun Modifier.terminalGestures(
    onDrag: (dy: Float) -> Unit,
    onPinch: (scale: Float) -> Unit,
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    isMouseReportingActive: () -> Boolean = { false },
    onMouseClick: (Offset) -> Unit = {},
    onMouseWheel: (Offset, dy: Float) -> Unit = { _, _ -> },
    onMouseSelect: (anchor: Offset, current: Offset) -> Unit = { _, _ -> },
    onMouseSelectEnd: () -> Unit = {},
    onMouseDoubleClick: (Offset) -> Unit = {},
    onSecondaryClick: (Offset) -> Unit = {},
    onWheelScroll: (Offset, dy: Float, shift: Boolean) -> Unit = { _, _, _ -> },
): Modifier =
    pointerInput(Unit) {
        // Outside awaitEachGesture because a double-click spans two gestures.
        var lastClickMillis = 0L
        var lastClickPos = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            val pointer1 = down.id

            if (down.type == PointerType.Mouse) {
                // Checked before the tracking test below: right-click is always the local
                // copy/paste, never a forwarded left click.
                if (currentEvent.buttons.isSecondaryPressed) {
                    onSecondaryClick(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) return@awaitEachGesture
                    }
                }
                if (!isMouseReportingActive() || currentEvent.keyboardModifiers.isShiftPressed) {
                    var selecting = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change1 = event.changes.firstOrNull { it.id == pointer1 }
                                ?: return@awaitEachGesture
                            if (!change1.pressed) {
                                change1.consume()
                                if (!selecting) {
                                    val now = System.currentTimeMillis()
                                    val isDoubleClick =
                                        now - lastClickMillis <=
                                            viewConfiguration.doubleTapTimeoutMillis &&
                                            distanceBetween(lastClickPos, change1.position) <=
                                            MOUSE_DRAG_SLOP_PX
                                    if (isDoubleClick) {
                                        lastClickMillis = 0L
                                        onMouseDoubleClick(change1.position)
                                    } else {
                                        lastClickMillis = now
                                        lastClickPos = change1.position
                                        onTap(change1.position)
                                    }
                                }
                                return@awaitEachGesture
                            }
                            if (change1.positionChange() != Offset.Zero) {
                                selecting = selecting ||
                                    distanceBetween(down.position, change1.position) >
                                    MOUSE_DRAG_SLOP_PX
                                if (selecting) onMouseSelect(down.position, change1.position)
                                change1.consume()
                            }
                        }
                    } finally {
                        if (selecting) onMouseSelectEnd()
                    }
                }
            }

            var pointer2: PointerId? = null
            var lastPos1 = down.position
            var pinchStartDistance = 0f
            var mode = GestureMode.UNDECIDED
            val downTimeMillis = System.currentTimeMillis()

            // Once the remote app turns on mouse tracking, touch means mouse for the rest of this
            // gesture: no long-press-select race, no timeout, no pinch ambiguity. Checked once at
            // the down, which is why it is a self-contained loop and not a state in the machine
            // below. A drag is the wheel, not a button drag: tmux, less and vim all scroll on
            // wheel events, while a left-button drag starts a selection in them, which on a phone
            // nobody wants from the one gesture that means "scroll" everywhere else.
            if (isMouseReportingActive()) {
                var wheeling = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change1 =
                        event.changes.firstOrNull { it.id == pointer1 } ?: return@awaitEachGesture
                    if (!change1.pressed) {
                        change1.consume()
                        if (!wheeling) onMouseClick(change1.position)
                        return@awaitEachGesture
                    }
                    val delta = change1.positionChange()
                    if (delta != Offset.Zero) {
                        wheeling = wheeling ||
                            distanceBetween(down.position, change1.position) > TOUCH_SLOP_PX
                        if (wheeling && delta.y != 0f) onMouseWheel(change1.position, delta.y)
                        change1.consume()
                    }
                }
            }

            while (mode == GestureMode.UNDECIDED) {
                val remaining =
                    LONG_PRESS_TIMEOUT_MS - (System.currentTimeMillis() - downTimeMillis)
                if (remaining <= 0) {
                    mode = GestureMode.LONG_PRESS
                    onLongPress(down.position)
                    break
                }
                val event = withTimeoutOrNull(remaining) { awaitPointerEvent() } ?: continue
                val pressed = event.changes.filter { it.pressed }

                if (pointer2 == null && pressed.size >= 2) {
                    val p1 = pressed.first { it.id == pointer1 }
                    val p2 = pressed.first { it.id != pointer1 }
                    pointer2 = p2.id
                    pinchStartDistance = distanceBetween(p1.position, p2.position).coerceAtLeast(1f)
                    mode = GestureMode.PINCH
                    break
                }

                val change1 = event.changes.firstOrNull { it.id == pointer1 } ?: break
                if (!change1.pressed) {
                    onTap(change1.position)
                    return@awaitEachGesture
                }
                if (change1.positionChange() != Offset.Zero) {
                    val travelled = distanceBetween(down.position, change1.position)
                    lastPos1 = change1.position
                    if (travelled > TOUCH_SLOP_PX) {
                        mode = GestureMode.DRAG
                        change1.consume()
                    }
                }
            }

            while (true) {
                val event = awaitPointerEvent()
                when (mode) {
                    GestureMode.PINCH -> {
                        val p1 = event.changes.firstOrNull { it.id == pointer1 }
                        val p2 = event.changes.firstOrNull { it.id == pointer2 }
                        if (p1 == null || p2 == null || !p1.pressed || !p2.pressed) return@awaitEachGesture
                        val distance = distanceBetween(p1.position, p2.position).coerceAtLeast(1f)
                        if (pinchStartDistance > 0f) onPinch(distance / pinchStartDistance)
                        pinchStartDistance = distance
                        p1.consume()
                        p2.consume()
                    }

                    GestureMode.DRAG -> {
                        val change1 = event.changes.firstOrNull { it.id == pointer1 }
                            ?: return@awaitEachGesture
                        if (!change1.pressed) return@awaitEachGesture
                        if (change1.positionChange() != Offset.Zero) {
                            onDrag(change1.position.y - lastPos1.y)
                            lastPos1 = change1.position
                            change1.consume()
                        }
                    }

                    GestureMode.LONG_PRESS -> {
                        if (event.changes.none { it.pressed }) return@awaitEachGesture
                        event.changes.forEach { it.consume() }
                    }

                    GestureMode.UNDECIDED -> return@awaitEachGesture // unreachable: the loop above always exits with a decided mode
                }
            }
        }
    }.pointerInput(Unit) {
        // Separate from the loop above: a scroll event has no press, so `awaitFirstDown` never
        // returns for it. Initial pass for the same reason as the down: the invisible field would
        // otherwise see it first.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.first()
                val shift = event.keyboardModifiers.isShiftPressed
                onWheelScroll(change.position, change.scrollDelta.y, shift)
                event.changes.forEach { it.consume() }
            }
        }
    }

private fun distanceBetween(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)

/** Whole rows in [remainderPx] and what is left over to carry into the next move event. */
fun dragRows(remainderPx: Float, cellHeightPx: Int): Pair<Int, Float> {
    val rows = (remainderPx / cellHeightPx).toInt()
    return rows to remainderPx - rows * cellHeightPx
}

/** Finger down (positive rows) shows older content, which is wheel up, as with the local scrollback. */
fun wheelButton(rows: Int): Int =
    if (rows > 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON

/**
 * Whole wheel notches in [remainder] plus [dy] and what is left over to carry into the next scroll
 * event. A touchpad reports fractions of a notch, which would otherwise each round to zero.
 * Positive is wheel toward the user.
 */
fun wheelNotches(remainder: Float, dy: Float): Pair<Int, Float> {
    val total = remainder + dy
    val notches = total.toInt()
    return notches to total - notches
}

/** What one wheel scroll does, by what the remote screen can make of it. */
sealed interface WheelAction {
    data class MouseReport(val button: Int, val count: Int) : WheelAction
    data class Arrows(val down: Boolean, val count: Int) : WheelAction

    /** Positive [rows] moves toward newer content. */
    data class Scrollback(val rows: Int) : WheelAction
}

/**
 * Picks the wheel's effect from the remote's mode, most specific first: an app that asked for mouse
 * tracking gets wheel events, one per notch; a full-screen app without it (`less`, `man`, plain
 * `vim`) has no scrollback to move, so arrow keys stand in as xterm's `alternateScroll` does; the
 * main screen scrolls the local scrollback. [notches] is positive for wheel toward the user, which
 * is down to newer content. Pure so the policy is testable without an emulator.
 */
fun wheelAction(notches: Int, mouseTracking: Boolean, alternateBuffer: Boolean): WheelAction? =
    when {
        notches == 0 -> null
        mouseTracking -> WheelAction.MouseReport(wheelButton(-notches), abs(notches))
        alternateBuffer ->
            WheelAction.Arrows(down = notches > 0, count = abs(notches) * WHEEL_ROWS_PER_NOTCH)
        else -> WheelAction.Scrollback(notches * WHEEL_ROWS_PER_NOTCH)
    }
