package io.github.lordofpolls.shellwave.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Regression test for the capture-timeout deadlock. Measured before the fix: a 3s `withTimeoutOrNull`
 * around `sleep 600` was still blocked past 15s, because cancellation never reaches a thread parked
 * in `readCapped`'s `stream.read()`.
 *
 * Needs shellwave-alpha on 127.0.0.1:2242 and SHELLWAVE_TEST_ALPHA_USER / _PASSWORD from the
 * environment - never hardcoded, per this repo's rule. Skips rather than fails when either is absent.
 */
class CaptureTimeoutWatchdogTest {
    private val host = "127.0.0.1"
    private val port = 2242
    private val user = System.getenv("SHELLWAVE_TEST_ALPHA_USER")
    private val pass = System.getenv("SHELLWAVE_TEST_ALPHA_PASSWORD")

    private fun containerReachable(): Boolean =
        user != null && pass != null &&
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 1_000) }
                true
            }.getOrDefault(false)

    // Only called after containerReachable() has gated on user/pass being set.
    private fun connectedClient(): SSHClient =
        SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connect(host, port)
            authPassword(user!!, pass!!)
        }

    /** Same shape as [ScriptRunner]'s fixed `connectExecCapture`. */
    private suspend fun CoroutineScope.execWithWatchdog(
        ssh: SSHClient,
        command: String,
        timeoutMs: Long,
    ): CaptureResult {
        var timedOut = false
        return try {
            coroutineScope {
                val watchdog = launch {
                    delay(timeoutMs)
                    timedOut = true
                    runCatching { ssh.disconnect() }
                }
                try {
                    execAndCollect(ssh, command)
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: Exception) {
            if (timedOut) {
                CaptureResult("", "", false, false, null, error = "Timed out after ${timeoutMs / 1000}s")
            } else {
                throw e
            }
        }
    }

    @Test
    fun hungCommand_isUnblockedByWatchdogNearTheDeadline() {
        assumeTrue("shellwave-alpha not reachable on $host:$port", containerReachable())

        val timeoutMs = 3_000L
        val ssh = connectedClient()
        val start = System.nanoTime()
        val elapsedNanos = AtomicLong(-1)
        val resultRef = arrayOfNulls<CaptureResult>(1)
        val done = CountDownLatch(1)

        val worker = Thread {
            // Dispatchers.IO: the watchdog needs a different real thread than the blocked reader,
            // or a single-threaded loop starves it as badly as cancellation does.
            runBlocking(Dispatchers.IO) {
                resultRef[0] = execWithWatchdog(ssh, "sleep 600", timeoutMs)
            }
            elapsedNanos.set(System.nanoTime() - start)
            done.countDown()
        }
        worker.isDaemon = true
        worker.start()

        // Before the fix this did not return within 15s of a 3s deadline.
        val finishedWithinBound = done.await(10, TimeUnit.SECONDS)
        runCatching { ssh.disconnect() }

        assertTrue("watchdog did not unblock the hung read within 10s", finishedWithinBound)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos.get())
        println("CaptureTimeoutWatchdogTest: timeoutMs=$timeoutMs elapsedMs=$elapsedMs result=${resultRef[0]}")

        assertTrue("expected to return near the ${timeoutMs}ms deadline, took ${elapsedMs}ms", elapsedMs < 8_000)
        assertTrue(
            "expected the timeout CaptureResult shape",
            resultRef[0]?.error == "Timed out after ${timeoutMs / 1000}s"
        )
    }

    @Test
    fun quickCommand_returnsImmediatelyAndUntouchedByTheWatchdog() {
        assumeTrue("shellwave-alpha not reachable on $host:$port", containerReachable())

        val ssh = connectedClient()
        val start = System.nanoTime()
        val result = runBlocking(Dispatchers.IO) { execWithWatchdog(ssh, "echo hi", 3_000L) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        runCatching { ssh.disconnect() }

        assertTrue("expected the echo to succeed, got $result", result.error == null)
        assertTrue("stdout should contain the echoed text, got '${result.stdout}'", result.stdout.contains("hi"))
        assertTrue("fast command took ${elapsedMs}ms, watchdog may be interfering", elapsedMs < 2_000)
    }
}
