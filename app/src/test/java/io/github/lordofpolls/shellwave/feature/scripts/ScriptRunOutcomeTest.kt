package io.github.lordofpolls.shellwave.feature.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunOutcomeTest {

    @Test
    fun `a successful run shows its output, not a platitude`() {
        assertEquals("up 3 days", runOutcomeMessage(0, "up 3 days\n", ""))
    }

    @Test
    fun `a successful run that printed nothing still says something`() {
        assertEquals("Finished successfully.", runOutcomeMessage(0, "", ""))
    }

    @Test
    fun `stderr stands in when stdout is empty`() {
        assertEquals("no such file", runOutcomeMessage(0, "", "no such file\n"))
    }

    @Test
    fun `a failing run leads with the status and keeps the output`() {
        val message = runOutcomeMessage(3, "", "no such file\n")
        assertEquals("Exited with status 3.\nno such file", message)
    }

    @Test
    fun `a failing run that printed nothing does not trail a blank line`() {
        assertEquals("Exited with status 1.", runOutcomeMessage(1, "", ""))
    }

    @Test
    fun `an unknown exit status is not reported as a number`() {
        val message = runOutcomeMessage(null, "", "")
        assertEquals("Finished - exit status unknown.", message)
    }

    @Test
    fun `a truncation marker survives into the notification`() {
        val message = runOutcomeMessage(0, mark("a lot of output", truncated = true), "")
        assertTrue(message, message.contains("truncated"))
    }
}
