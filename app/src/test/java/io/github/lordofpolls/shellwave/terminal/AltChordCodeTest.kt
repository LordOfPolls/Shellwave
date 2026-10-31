package io.github.lordofpolls.shellwave.terminal

import android.view.KeyEvent.META_ALT_LEFT_ON
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_ALT_RIGHT_ON
import android.view.KeyEvent.META_SHIFT_ON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val LEFT_ALT = META_ALT_ON or META_ALT_LEFT_ON
private const val RIGHT_ALT = META_ALT_ON or META_ALT_RIGHT_ON
private const val COMBINING_ACUTE = 0x0301 or Int.MIN_VALUE

class AltChordCodeTest {
    /** Mirrors keyboard_layout_german.kcm's `key Q`. */
    private val germanQ = { meta: Int ->
        when {
            meta and META_ALT_RIGHT_ON != 0 -> '@'.code
            meta and META_ALT_ON != 0 -> 0
            meta and META_SHIFT_ON != 0 -> 'Q'.code
            else -> 'q'.code
        }
    }

    /** Mirrors Generic.kcm's `key C`, whose `alt:` level answers to either Alt. */
    private val genericC = { meta: Int -> if (meta and META_ALT_ON != 0) 0xE7 else 'c'.code }

    private val usDot = { meta: Int -> if (meta and META_ALT_ON != 0) 0 else '.'.code }

    @Test
    fun `AltGr character goes to the field, not ESC`() {
        assertNull(altChordCode(RIGHT_ALT, germanQ))
        assertNull(altChordCode(RIGHT_ALT or META_SHIFT_ON, germanQ))
    }

    @Test
    fun `AltGr dead key goes to the field`() {
        assertNull(altChordCode(RIGHT_ALT) { if (it and META_ALT_RIGHT_ON != 0) COMBINING_ACUTE else 'e'.code })
    }

    @Test
    fun `left Alt stays meta on an AltGr layout`() {
        assertEquals("\u001Bq", altChordCode(LEFT_ALT, germanQ))
        assertEquals("\u001BQ", altChordCode(LEFT_ALT or META_SHIFT_ON, germanQ))
    }

    @Test
    fun `right Alt stays meta where the layout has no right Alt level`() {
        assertEquals("\u001B.", altChordCode(RIGHT_ALT, usDot))
        assertEquals("\u001Bc", altChordCode(RIGHT_ALT, genericC))
    }

    @Test
    fun `both Alts held stay meta`() {
        assertEquals("\u001Bq", altChordCode(RIGHT_ALT or LEFT_ALT, germanQ))
    }

    @Test
    fun `left Alt on a dead base key sends nothing`() {
        assertNull(altChordCode(LEFT_ALT) { if (it == 0) COMBINING_ACUTE else 0 })
    }
}
