package io.github.lordofpolls.shellwave.feature.scripts

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseChoicesTest {
    @Test
    fun `splits on commas, trims, and drops empty entries`() {
        assertEquals(
            listOf("nginx", "postgresql", "redis"),
            parseChoices(" nginx,postgresql , redis,, "),
        )
    }

    @Test
    fun `a trailing comma mid-typing yields the entries so far`() {
        assertEquals(listOf("nginx"), parseChoices("nginx,"))
    }
}
