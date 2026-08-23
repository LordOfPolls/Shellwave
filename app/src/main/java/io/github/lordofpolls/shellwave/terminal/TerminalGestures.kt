package io.github.lordofpolls.shellwave.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.hypot

private const val LONG_PRESS_TIMEOUT_MS = 500L

/** Only needs to tell a stationary long-press from a drag. */
private const val TOUCH_SLOP_PX = 24f

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
    onMousePress: (Offset) -> Unit = {},
    onMouseDrag: (Offset) -> Unit = {},
    onMouseRelease: (Offset) -> Unit = {},
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            val pointer1 = down.id
            var pointer2: PointerId? = null
            var lastPos1 = down.position
            var pinchStartDistance = 0f
            var mode = GestureMode.UNDECIDED
            val downTimeMillis = System.currentTimeMillis()

            // Once the remote app turns on mouse tracking, touch means mouse for the rest of this
            // gesture: no long-press-select race, no timeout, no pinch ambiguity. Checked once at
            // the down, which is why it is a self-contained loop and not a state in the machine
            // below.
            if (isMouseReportingActive()) {
                onMousePress(down.position)
                while (true) {
                    val event = awaitPointerEvent()
                    val change1 =
                        event.changes.firstOrNull { it.id == pointer1 } ?: return@awaitEachGesture
                    if (!change1.pressed) {
                        change1.consume()
                        onMouseRelease(change1.position)
                        return@awaitEachGesture
                    }
                    if (change1.positionChange() != Offset.Zero) {
                        onMouseDrag(change1.position)
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
    }

private fun distanceBetween(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)
