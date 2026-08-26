package io.github.lordofpolls.shellwave.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the interleaving that used to kill a slower run: a fast run's `stopSelf(startId)` tore down
 * the shared scope, taking the slower run's outcome notification and `script_runs` row with it.
 */
class InFlightRunCounterTest {

    @Test
    fun `a fresh counter is idle`() {
        assertTrue(InFlightRunCounter().isIdle())
    }

    @Test
    fun `one run starting and finishing reports idle`() {
        val counter = InFlightRunCounter()
        counter.runStarted()

        assertTrue("the only run finishing should be the cue to stop", counter.runFinished())
        assertTrue(counter.isIdle())
    }

    @Test
    fun `a fast run finishing first does not claim the service can stop while a slow run is still going`() {
        val counter = InFlightRunCounter()
        counter.runStarted() // A, slow
        counter.runStarted() // B, fast

        assertFalse(
            "B finishing first must not signal idle while A is still in flight",
            counter.runFinished()
        )
        assertFalse(counter.isIdle())

        assertTrue("A finishing last is the real cue to stop", counter.runFinished())
        assertTrue(counter.isIdle())
    }

    @Test
    fun `count reflects how many runs are in flight`() {
        val counter = InFlightRunCounter()
        assertTrue(counter.count() == 0)

        counter.runStarted()
        counter.runStarted()
        assertTrue(counter.count() == 2)

        counter.runFinished()
        assertTrue(counter.count() == 1)
    }
}
