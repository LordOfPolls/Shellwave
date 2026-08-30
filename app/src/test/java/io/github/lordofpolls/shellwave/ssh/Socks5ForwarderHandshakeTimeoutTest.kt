package io.github.lordofpolls.shellwave.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Anything past the handshake needs a live SSH connection, so this covers only the new deadline. */
class Socks5ForwarderHandshakeTimeoutTest {

    @Test
    fun stalledHandshake_isClosedByDeadline() {
        val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val forwarder = Socks5Forwarder(
            SSHClient(),
            serverSocket,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val listenerThread = Thread(forwarder::listen).apply { isDaemon = true; start() }

        try {
            Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort).use { client ->
                // Never send the greeting; margin so this asserts the server gave up, not us.
                client.soTimeout = HANDSHAKE_TIMEOUT_MS + 5000
                val readResult = client.getInputStream().read()
                assertEquals(
                    "server should have closed the stalled connection, not sent data",
                    -1,
                    readResult
                )
            }
        } finally {
            forwarder.close()
            listenerThread.join(1000)
        }
    }
}
