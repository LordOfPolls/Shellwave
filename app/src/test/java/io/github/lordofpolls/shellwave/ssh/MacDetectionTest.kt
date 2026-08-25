package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacDetectionTest {
    @Test
    fun readsTheAddressSysfsPrints() {
        assertEquals("3c:22:fb:01:02:03", macFromDetectionOutput("3c:22:fb:01:02:03\n"))
        assertEquals("3c:22:fb:01:02:03", macFromDetectionOutput("  3C:22:FB:01:02:03  "))
    }

    @Test
    fun rejectsTheLoopbackAddress() {
        // What `lo` reports when the connection reached the host over localhost or a tunnel. It
        // parses, and it would wake nothing.
        assertNull(macFromDetectionOutput("00:00:00:00:00:00\n"))
    }

    @Test
    fun rejectsOutputThatIsNotAMac() {
        assertNull(macFromDetectionOutput(""))
        assertNull(macFromDetectionOutput("\n"))
        assertNull(macFromDetectionOutput("cat: /sys/class/net//address: No such file or directory"))
    }

    @Test
    fun takesOnlyTheFirstLine() {
        assertEquals(
            "3c:22:fb:01:02:03",
            macFromDetectionOutput("3c:22:fb:01:02:03\nsomething else\n"),
        )
    }

    @Test
    fun theCommandSelectsTheInterfaceTheConnectionArrivedOn() {
        // The whole point of the command: SSH_CONNECTION's server address decides the interface, so
        // a docker0 or virbr0 listed first cannot be picked instead.
        assertTrue(MAC_DETECTION_COMMAND.contains("\$SSH_CONNECTION"))
        assertTrue(MAC_DETECTION_COMMAND.contains("/sys/class/net/"))
    }
}
