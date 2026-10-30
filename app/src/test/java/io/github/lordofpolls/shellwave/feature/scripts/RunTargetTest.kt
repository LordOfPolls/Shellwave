package io.github.lordofpolls.shellwave.feature.scripts

import org.junit.Assert.assertEquals
import org.junit.Test

class RunTargetTest {
    @Test
    fun `names the host a run will land on`() {
        assertEquals("betty, staying attached", runTarget("ATTACH", "betty", onCurrentSession = false))
        assertEquals("betty, capturing output", runTarget("CAPTURE", "betty", onCurrentSession = false))
    }

    @Test
    fun `a capture run on an open connection names the session, not the script's host`() {
        assertEquals(
            "the current session, capturing output",
            runTarget("CAPTURE", "betty", onCurrentSession = true),
        )
    }

    @Test
    fun `an unresolved host falls back to generic wording`() {
        assertEquals("the target host, staying attached", runTarget("ATTACH", null, onCurrentSession = false))
    }
}
