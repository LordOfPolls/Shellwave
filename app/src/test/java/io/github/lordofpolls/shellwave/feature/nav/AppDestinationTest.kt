package io.github.lordofpolls.shellwave.feature.nav

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure navigation logic behind the four-destination skeleton ([popNav], [isNavAtRoot],
 * [useNavigationRail]) - the Compose state built on top of these (MainActivity's
 * `destination`/`subStack`) isn't unit-testable off-device, but the decisions themselves are plain
 * functions and are tested here per the phase brief's "add JVM unit tests for any pure navigation
 * logic you introduce".
 */
class AppDestinationTest {

    // --- popNav --------------------------------------------------------------------------

    @Test
    fun `popNav pops a pushed screen before touching the destination`() {
        val result = popNav(AppDestination.SETTINGS, listOf("license"))
        assertEquals(AppDestination.SETTINGS to emptyList<String>(), result)
    }

    @Test
    fun `popNav pops the deepest of several pushed screens`() {
        val result = popNav(AppDestination.HOSTS, listOf("scripts", "addEditScript"))
        assertEquals(AppDestination.HOSTS to listOf("scripts"), result)
    }

    @Test
    fun `popNav falls back to Hosts once subStack is empty`() {
        val result = popNav(AppDestination.SESSIONS, emptyList())
        assertEquals(AppDestination.HOSTS to emptyList<String>(), result)
    }

    @Test
    fun `popNav returns null at the true root`() {
        assertNull(popNav(AppDestination.HOSTS, emptyList()))
    }

    @Test
    fun `popNav from Sessions reaches Hosts in one step`() {
        // Mirrors the documented defect fix: back from a session (however it was opened) must land
        // on Home/Hosts specifically, not exit or land elsewhere.
        val afterOneBack = popNav(AppDestination.SESSIONS, emptyList())
        assertEquals(AppDestination.HOSTS, afterOneBack?.first)
        assertTrue(afterOneBack!!.second.isEmpty())
    }

    // --- isNavAtRoot -----------------------------------------------------------------------

    @Test
    fun `isNavAtRoot is true only at Hosts with nothing pushed`() {
        assertTrue(isNavAtRoot(AppDestination.HOSTS, emptyList()))
        assertFalse(isNavAtRoot(AppDestination.HOSTS, listOf("addEdit")))
        assertFalse(isNavAtRoot(AppDestination.SESSIONS, emptyList()))
        assertFalse(isNavAtRoot(AppDestination.SCRIPTS, emptyList()))
        assertFalse(isNavAtRoot(AppDestination.SETTINGS, emptyList()))
    }

    // --- useNavigationRail -------------------------------------------------------------------

    private fun windowSizeClass(widthDp: Float, heightDp: Float): WindowSizeClass =
        WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(
            widthDp = widthDp,
            heightDp = heightDp
        )

    @Test
    fun `useNavigationRail is false on a compact (phone) width`() {
        assertFalse(useNavigationRail(windowSizeClass(360f, 800f)))
    }

    @Test
    fun `useNavigationRail is true at the medium breakpoint`() {
        assertTrue(useNavigationRail(windowSizeClass(600f, 900f)))
    }

    @Test
    fun `useNavigationRail is true on an expanded (tablet) width`() {
        assertTrue(useNavigationRail(windowSizeClass(1000f, 800f)))
    }
}
