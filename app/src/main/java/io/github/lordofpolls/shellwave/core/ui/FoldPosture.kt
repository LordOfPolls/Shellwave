package io.github.lordofpolls.shellwave.core.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeRect
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/**
 * Narrower than material3-adaptive's own [androidx.compose.material3.adaptive.Posture], which
 * already keeps [ListDetailPaneScaffold] off a hinge. This exists for what that doesn't cover:
 * telling [Book] from Flat, and carrying hinge bounds far enough to build the vertical split
 * [Tabletop] wants, with the terminal above the hinge and the key bar below it.
 */
sealed class FoldPosture {
    /** No fold, or a fold reported fully open flat - normal single continuous surface. */
    data object Flat : FoldPosture()

    /** Half-opened with a vertical hinge (splits the window into left/right halves), like an open book. */
    data object Book : FoldPosture()

    /**
     * Half-opened with a horizontal hinge (splits the window into top/bottom halves), like a tent or a
     * laptop propped flat on a table. [hingeBoundsPx] is the hinge's bounds in window pixel
     * coordinates, as reported by [FoldingFeature.bounds]: convert into a composable's local
     * coordinates with `LayoutCoordinates.positionInWindow()` before using it to size children (see the
     * session screen's tabletop layout).
     */
    data class Tabletop(val hingeBoundsPx: Rect) : FoldPosture()
}

/**
 * Live `FoldPosture` for [activity], updated as the device folds/unfolds. Plain `collectAsState()`
 * instead of a `lifecycle-runtime-compose` dependency for `collectAsStateWithLifecycle`.
 */
@Composable
fun rememberFoldPosture(activity: Activity): State<FoldPosture> {
    val tracker = remember(activity) { WindowInfoTracker.getOrCreate(activity) }
    val postureFlow = remember(tracker, activity) {
        tracker.windowLayoutInfo(activity).map { info -> classifyPosture(info.displayFeatures) }
    }
    return postureFlow.collectAsState(initial = FoldPosture.Flat)
}

private fun classifyPosture(features: List<DisplayFeature>): FoldPosture {
    val fold = features.filterIsInstance<FoldingFeature>().firstOrNull() ?: return FoldPosture.Flat
    if (fold.state != FoldingFeature.State.HALF_OPENED) return FoldPosture.Flat
    return if (fold.orientation == FoldingFeature.Orientation.HORIZONTAL) {
        FoldPosture.Tabletop(fold.bounds.toComposeRect())
    } else {
        FoldPosture.Book
    }
}
