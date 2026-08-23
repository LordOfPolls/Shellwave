package io.github.lordofpolls.shellwave.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.window.core.layout.WindowSizeClass

/**
 * There is no Tools hub, because a destination should land on content and not a directory of links:
 * key enrolment and port forwards are per-host and sit in the host editor, file transfer is
 * per-session and sits in the terminal overflow.
 */
enum class AppDestination(val label: String, val icon: ImageVector) {
    HOSTS("Hosts", Icons.Outlined.Dns),
    SESSIONS("Sessions", Icons.Outlined.Terminal),

    SCRIPTS("Scripts", Icons.Outlined.Code),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/**
 * Anything pushed on top pops first, then any peer destination falls back to Hosts in one step.
 * `null` at the true root, where the caller lets the system finish the Activity.
 */
fun popNav(
    destination: AppDestination,
    subStack: List<String>
): Pair<AppDestination, List<String>>? =
    when {
        subStack.isNotEmpty() -> destination to subStack.dropLast(1)
        destination != AppDestination.HOSTS -> AppDestination.HOSTS to emptyList()
        else -> null
    }

fun isNavAtRoot(destination: AppDestination, subStack: List<String>): Boolean =
    destination == AppDestination.HOSTS && subStack.isEmpty()

/** Off the same `WindowSizeClass` material3-adaptive uses internally. */
fun useNavigationRail(windowSizeClass: WindowSizeClass): Boolean =
    windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
