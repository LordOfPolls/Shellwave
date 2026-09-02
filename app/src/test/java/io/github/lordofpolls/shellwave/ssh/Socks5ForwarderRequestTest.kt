package io.github.lordofpolls.shellwave.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

private const val CLIENT_SOCKET_TIMEOUT_MS = 5000

/**
 * Drives the SOCKS5 handshake and request parsing over a real loopback socket, same as
 * [Socks5ForwarderHandshakeTimeoutTest]. `openChannel` is a test seam: the real path needs a live
 * SSH session, so it's swapped for a lambda that records what it was asked to connect to and
 * throws, which is enough to observe parsing without a real channel.
 */
class Socks5ForwarderRequestTest {

    private lateinit var forwarder: Socks5Forwarder
    private lateinit var serverSocket: ServerSocket
    private lateinit var ssh: SSHClient
    private lateinit var scope: CoroutineScope

    private fun startForwarder(
        openChannel: (String, Int) -> DirectConnection,
    ): ServerSocket {
        serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        ssh = SSHClient()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        forwarder = Socks5Forwarder(ssh, serverSocket, scope, openChannel)
        Thread(forwarder::listen).apply { isDaemon = true; start() }
        return serverSocket
    }

    private fun clientSocket(port: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = CLIENT_SOCKET_TIMEOUT_MS }

    @After
    fun tearDown() {
        forwarder.close()
        scope.cancel()
        ssh.close()
        serverSocket.close()
    }

    @Test
    fun `well-formed CONNECT with domain name is parsed into host and port`() {
        val requested = AtomicReference<Pair<String, Int>?>()
        val serverSocket = startForwarder { host, port ->
            requested.set(host to port)
            throw IOException("no real ssh session in this test")
        }

        clientSocket(serverSocket.localPort).use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()

            output.write(byteArrayOf(5, 1, 0)) // VER, NMETHODS=1, NO_AUTH
            output.flush()
            assertEquals(5, input.read())
            assertEquals(0x00, input.read())

            val domain = "example.com"
            val request =
                byteArrayOf(5, 1, 0, 3, domain.length.toByte()) +
                    domain.toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0x1F, 0x90.toByte()) // port 8080
            output.write(request)
            output.flush()

            // Reply header - REPLY_GENERAL_FAILURE since openChannel throws.
            assertEquals(5, input.read())
            assertEquals(0x01, input.read())
        }

        assertEquals("example.com" to 8080, requested.get())
    }

    @Test
    fun `well-formed CONNECT with IPv6 address is parsed into the literal, no reverse DNS`() {
        val requested = AtomicReference<Pair<String, Int>?>()
        val serverSocket = startForwarder { host, port ->
            requested.set(host to port)
            throw IOException("no real ssh session in this test")
        }

        clientSocket(serverSocket.localPort).use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()

            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            input.read()
            input.read()

            // ::1, port 443.
            val addressBytes = ByteArray(16).also { it[15] = 1 }
            val request = byteArrayOf(5, 1, 0, 4) + addressBytes + byteArrayOf(1, 187.toByte())
            output.write(request)
            output.flush()

            assertEquals(5, input.read())
            assertEquals(0x01, input.read()) // REPLY_GENERAL_FAILURE since openChannel throws.
        }

        assertEquals("0:0:0:0:0:0:0:1" to 443, requested.get())
    }

    @Test
    fun `unsupported address type is refused with SOCKS5 ADDRESS_TYPE_NOT_SUPPORTED`() {
        val serverSocket = startForwarder { _, _ -> error("must not be called") }

        clientSocket(serverSocket.localPort).use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()

            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            input.read()
            input.read()

            output.write(byteArrayOf(5, 1, 0, 9)) // ATYP 0x09 doesn't exist
            output.flush()

            assertEquals(5, input.read())
            assertEquals(0x08, input.read())
        }
    }

    @Test
    fun `unsupported auth method is refused with SOCKS5 NO_ACCEPTABLE_METHODS`() {
        val serverSocket = startForwarder { _, _ -> error("must not be called") }

        clientSocket(serverSocket.localPort).use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()

            output.write(byteArrayOf(5, 1, 0x02)) // only username/password on offer
            output.flush()

            assertEquals(5, input.read())
            assertEquals(0xFF, input.read())
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `unsupported command is refused with SOCKS5 COMMAND_NOT_SUPPORTED`() {
        val serverSocket = startForwarder { _, _ -> error("must not be called") }

        clientSocket(serverSocket.localPort).use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()

            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            input.read()
            input.read()

            // BIND (0x02) instead of CONNECT, IPv4 address type.
            output.write(byteArrayOf(5, 2, 0, 1, 127, 0, 0, 1, 0, 80))
            output.flush()

            assertEquals(5, input.read())
            assertEquals(0x07, input.read())
        }
    }

    @Test
    fun `truncated request does not crash the forwarder`() {
        val requested = AtomicReference<Pair<String, Int>?>()
        val serverSocket = startForwarder { host, port ->
            requested.set(host to port)
            throw IOException("no real ssh session in this test")
        }

        clientSocket(serverSocket.localPort).use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()

            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            input.read()
            input.read()

            // Claims a 10-byte domain but only sends 2, then closes - EOF mid-parse.
            output.write(byteArrayOf(5, 1, 0, 3, 10, 'a'.code.toByte(), 'b'.code.toByte()))
            output.flush()
        }

        // The accept loop must still be alive and able to serve a fresh, well-formed connection.
        clientSocket(serverSocket.localPort).use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            input.read()
            input.read()
            output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 80))
            output.flush()
            input.read()
        }
        assertEquals("127.0.0.1" to 80, requested.get())
    }
}
