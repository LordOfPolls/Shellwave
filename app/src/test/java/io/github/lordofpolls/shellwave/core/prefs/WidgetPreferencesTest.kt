package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetPreferencesTest {

    private val prefs = FakeSharedPreferences()
    private val context: Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun `a fresh install has no WOL tile host`() {
        assertNull(WidgetPreferences.wolTileHostId(context))
    }

    @Test
    fun `setting and reading round-trips`() {
        WidgetPreferences.setWolTileHostId(context, 7L)
        assertEquals(7L, WidgetPreferences.wolTileHostId(context))
    }

    @Test
    fun `setting null clears the host`() {
        WidgetPreferences.setWolTileHostId(context, 7L)
        WidgetPreferences.setWolTileHostId(context, null)
        assertNull(WidgetPreferences.wolTileHostId(context))
    }

    /** The two ids share one prefs file and the same `-1L` sentinel. */
    @Test
    fun `setting the WOL host doesn't disturb the QS tile script`() {
        WidgetPreferences.setQsTileScriptId(context, 3L)
        WidgetPreferences.setWolTileHostId(context, 7L)
        assertEquals(3L, WidgetPreferences.qsTileScriptId(context))
        assertEquals(7L, WidgetPreferences.wolTileHostId(context))
    }
}
