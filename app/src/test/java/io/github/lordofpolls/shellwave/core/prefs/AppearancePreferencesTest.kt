package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearancePreferencesTest {

    private val prefs = FakeSharedPreferences()
    private val context: Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun `a fresh install is dynamic`() {
        assertEquals(ColourTheme.DYNAMIC, AppearancePreferences.getColourTheme(context))
    }

    /** Upgraders who had switched dynamic colour off must not be silently reset. */
    @Test
    fun `the legacy boolean maps to schematic`() {
        prefs.edit().putBoolean("dynamic_color", false).apply()
        assertEquals(ColourTheme.SCHEMATIC, AppearancePreferences.getColourTheme(context))
    }

    @Test
    fun `an explicit choice beats the legacy boolean`() {
        prefs.edit().putBoolean("dynamic_color", false).apply()
        AppearancePreferences.setColourTheme(context, ColourTheme.NOTHING)
        assertEquals(ColourTheme.NOTHING, AppearancePreferences.getColourTheme(context))
    }
}
