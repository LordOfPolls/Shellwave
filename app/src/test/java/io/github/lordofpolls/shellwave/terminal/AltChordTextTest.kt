package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AltChordTextTest {
    @Test
    fun leftAlt_sendsEscapePrefixedBaseCharacter() {
        assertEquals("\u001B.", altChordText(rightAlt = false, composed = '.'.code, base = '.'.code))
    }

    @Test
    fun leftAlt_ignoresComposedCharacter() {
        assertEquals("\u001Bq", altChordText(rightAlt = false, composed = '@'.code, base = 'q'.code))
    }

    @Test
    fun altGr_sendsComposedCharacterWithoutEscape() {
        assertEquals("@", altChordText(rightAlt = true, composed = '@'.code, base = 'q'.code))
        assertEquals("[", altChordText(rightAlt = true, composed = '['.code, base = '8'.code))
    }

    @Test
    fun rightAlt_withoutComposedCharacter_actsAsMeta() {
        assertEquals("\u001Bx", altChordText(rightAlt = true, composed = 'x'.code, base = 'x'.code))
    }

    @Test
    fun rightAlt_noMapping_zeroComposedCharacter_actsAsMeta() {
        assertEquals("\u001Bx", altChordText(rightAlt = true, composed = 0, base = 'x'.code))
    }

    @Test
    fun altGr_supplementaryCodePoint() {
        assertEquals("😀", altChordText(rightAlt = true, composed = 0x1F600, base = 'e'.code))
    }

    @Test
    fun unprintableKey_isNull() {
        assertNull(altChordText(rightAlt = false, composed = 0, base = 0))
        assertNull(altChordText(rightAlt = true, composed = 0, base = 0))
    }
}
