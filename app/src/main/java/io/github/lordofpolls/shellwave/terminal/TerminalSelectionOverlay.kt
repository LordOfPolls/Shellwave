package io.github.lordofpolls.shellwave.terminal

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.termux.terminal.TerminalEmulator
import kotlin.math.max
import kotlin.math.min

private const val ACTION_COPY = 1
private const val ACTION_SELECT_ALL = 2
private const val ACTION_PASTE = 3
private const val ACTION_SHARE = 4

private const val LEFT_HANDLE_HOTSPOT_FRACTION = 0.75f
private const val RIGHT_HANDLE_HOTSPOT_FRACTION = 0.25f

/**
 * The view half of the selection controller; TerminalSelectionState is the model half.
 *
 * Handles live in their own [Popup], away from the terminal's gesture surface. A `Popup` is a real
 * separate window, so touch dispatch routes a finger on a handle straight to it and
 * [terminalGestures] never sees it, with no flag needed to tell the two apart.
 *
 * The menu is the platform's floating [ActionMode], the same one `BasicTextField` and upstream's
 * `TextSelectionCursorController` use, reached via `LocalView` since Compose has no wrapper for it.
 */
@Composable
fun TerminalSelectionOverlay(
    state: TerminalSelectionState,
    emulator: TerminalEmulator?,
    cellWidthPx: Int,
    cellHeightPx: Int,
    topRow: Int,
    onCopy: (String) -> Unit,
    onPaste: () -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sel = state.selection?.normalized()
    var boxOffsetInRoot by remember { mutableStateOf(Offset.Zero) }

    // A floating ActionMode doesn't intercept the back gesture the way the classic contextual
    // action bar did, so back would otherwise fall through to MainActivity's navigation BackHandler
    // and leave the session screen entirely with a selection still open.
    BackHandler(enabled = sel != null) { state.clear() }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    boxOffsetInRoot = coordinates.positionInRoot()
                },
    ) {
        TerminalSelectionActionMode(
            active = sel != null && emulator != null,
            emulator = emulator,
            selection = sel,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
            topRow = topRow,
            boxOffsetInRoot = boxOffsetInRoot,
            state = state,
            onCopy = onCopy,
            onPaste = onPaste,
            onShare = onShare,
        )

        if (sel != null && emulator != null && cellWidthPx > 0 && cellHeightPx > 0) {
            SelectionHandlePopup(
                handleAttr = android.R.attr.textSelectHandleLeft,
                hotspotFraction = LEFT_HANDLE_HOTSPOT_FRACTION,
                targetXPx = sel.startCol * cellWidthPx,
                targetYPx = (sel.startRow - topRow + 1) * cellHeightPx,
                onDrag = { dx, dy ->
                    state.dragHandle(
                        emulator,
                        cellWidthPx,
                        cellHeightPx,
                        isStart = true,
                        dx = dx,
                        dy = dy
                    )
                },
            )
            SelectionHandlePopup(
                handleAttr = android.R.attr.textSelectHandleRight,
                hotspotFraction = RIGHT_HANDLE_HOTSPOT_FRACTION,
                targetXPx = (sel.endCol + 1) * cellWidthPx,
                targetYPx = (sel.endRow - topRow + 1) * cellHeightPx,
                onDrag = { dx, dy ->
                    state.dragHandle(
                        emulator,
                        cellWidthPx,
                        cellHeightPx,
                        isStart = false,
                        dx = dx,
                        dy = dy
                    )
                },
            )
        }
    }
}

/**
 * Via the theme attribute, the public route: the `android.R.drawable` ids are framework-internal.
 * It is also what `TextView` resolves internally, so these are exactly the same bitmaps.
 */
private fun resolveHandleDrawable(context: Context, attrId: Int): Drawable? {
    val typedArray = context.obtainStyledAttributes(intArrayOf(attrId))
    return try {
        typedArray.getDrawable(0)
    } finally {
        typedArray.recycle()
    }
}

/** Positioned so [hotspotFraction] of its own width lands on ([targetXPx], [targetYPx]). */
@Composable
private fun SelectionHandlePopup(
    handleAttr: Int,
    hotspotFraction: Float,
    targetXPx: Int,
    targetYPx: Int,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val (widthPx, heightPx) =
        remember(context, handleAttr) {
            val drawable = resolveHandleDrawable(context, handleAttr)
            (drawable?.intrinsicWidth ?: 0) to (drawable?.intrinsicHeight ?: 0)
        }
    if (widthPx <= 0 || heightPx <= 0) return

    val popupX = targetXPx - (widthPx * hotspotFraction).toInt()
    val popupY = targetYPx

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(popupX, popupY),
        properties = PopupProperties(
            focusable = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        ),
    ) {
        Box(
            modifier =
                Modifier
                    .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            var lastPosition = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                onDrag(
                                    change.position.x - lastPosition.x,
                                    change.position.y - lastPosition.y
                                )
                                lastPosition = change.position
                            }
                        }
                    },
        ) {
            AndroidView(factory = { ctx ->
                ImageView(ctx).apply {
                    setImageDrawable(
                        resolveHandleDrawable(ctx, handleAttr)
                    )
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Refreshed via [rememberUpdatedState] so the one long-lived callback never reads stale values. */
private class SelectionMenuContext(
    val emulator: TerminalEmulator?,
    val selection: TerminalSelection?,
    val cellWidthPx: Int,
    val cellHeightPx: Int,
    val topRow: Int,
    val boxOffsetInRoot: Offset,
    val state: TerminalSelectionState,
    val onCopy: (String) -> Unit,
    val onPaste: () -> Unit,
    val onShare: (String) -> Unit,
)

/**
 * `startActionMode` fires only on the false -> true edge; reopening it on every recomposition would
 * fight the user for focus. [SelectionMenuContext] then carries what the callback needs for the
 * rest of that selection's life, including across a reconnect that swaps in a new TerminalEmulator.
 */
@Composable
private fun TerminalSelectionActionMode(
    active: Boolean,
    emulator: TerminalEmulator?,
    selection: TerminalSelection?,
    cellWidthPx: Int,
    cellHeightPx: Int,
    topRow: Int,
    boxOffsetInRoot: Offset,
    state: TerminalSelectionState,
    onCopy: (String) -> Unit,
    onPaste: () -> Unit,
    onShare: (String) -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val current by
    rememberUpdatedState(
        SelectionMenuContext(
            emulator,
            selection,
            cellWidthPx,
            cellHeightPx,
            topRow,
            boxOffsetInRoot,
            state,
            onCopy,
            onPaste,
            onShare
        ),
    )
    var actionMode by remember { mutableStateOf<ActionMode?>(null) }

    DisposableEffect(active) {
        actionMode =
            if (active) {
                view.startActionMode(
                    object : ActionMode.Callback2() {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val show =
                                MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, "Copy")
                                .setShowAsAction(show)
                            menu.add(Menu.NONE, ACTION_SELECT_ALL, Menu.NONE, "Select All")
                                .setShowAsAction(show)
                            menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, "Paste")
                                .setEnabled(clipboard?.hasPrimaryClip() == true)
                                .setShowAsAction(show)
                            menu.add(Menu.NONE, ACTION_SHARE, Menu.NONE, "Share")
                                .setShowAsAction(show)
                            return true
                        }

                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                            false

                        override fun onActionItemClicked(
                            mode: ActionMode,
                            item: MenuItem
                        ): Boolean {
                            val ctx = current
                            val activeEmulator = ctx.emulator
                            when (item.itemId) {
                                ACTION_COPY -> {
                                    if (activeEmulator != null) ctx.state.selectedText(
                                        activeEmulator
                                    )?.let(ctx.onCopy)
                                    ctx.state.clear()
                                    mode.finish()
                                }

                                ACTION_SELECT_ALL -> {
                                    if (activeEmulator != null) ctx.state.selectAll(activeEmulator)
                                    mode.invalidateContentRect()
                                }

                                ACTION_PASTE -> {
                                    ctx.onPaste()
                                    ctx.state.clear()
                                    mode.finish()
                                }

                                ACTION_SHARE -> {
                                    if (activeEmulator != null) ctx.state.selectedText(
                                        activeEmulator
                                    )?.let(ctx.onShare)
                                }
                            }
                            return true
                        }

                        override fun onDestroyActionMode(mode: ActionMode) {
                            actionMode = null
                            current.state.clear()
                        }

                        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                            val ctx = current
                            val sel = ctx.selection
                            if (sel == null || ctx.cellWidthPx <= 0 || ctx.cellHeightPx <= 0) return
                            val left = min(sel.startCol, sel.endCol) * ctx.cellWidthPx
                            val right = (max(sel.startCol, sel.endCol) + 1) * ctx.cellWidthPx
                            val top =
                                ((sel.startRow - ctx.topRow) * ctx.cellHeightPx).coerceAtLeast(0)
                            val bottom = (sel.endRow - ctx.topRow + 1) * ctx.cellHeightPx
                            outRect.set(
                                (ctx.boxOffsetInRoot.x + left).toInt(),
                                (ctx.boxOffsetInRoot.y + top).toInt(),
                                (ctx.boxOffsetInRoot.x + right).toInt(),
                                (ctx.boxOffsetInRoot.y + bottom).toInt(),
                            )
                        }
                    },
                    ActionMode.TYPE_FLOATING,
                )
            } else {
                null
            }
        onDispose { actionMode?.finish() }
    }

    // The mode object survives the whole selection; only its position and, after Select All, its
    // content change. Nudge it to re-query onGetContentRect instead of recreating it per frame.
    LaunchedEffect(selection, cellWidthPx, cellHeightPx, topRow, boxOffsetInRoot) {
        actionMode?.invalidateContentRect()
    }
}
