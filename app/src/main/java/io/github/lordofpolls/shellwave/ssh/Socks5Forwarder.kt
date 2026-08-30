package io.github.lordofpolls.shellwave.ssh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

private const val LOG_TAG = "Socks5Forwarder"
private const val PUMP_BUFFER_SIZE = 8192

// Bounds a client that stalls mid-handshake; cleared before the relay phase.
internal const val HANDSHAKE_TIMEOUT_MS = 5000

private const val SOCKS_VERSION = 5
private const val METHOD_NO_AUTH: Byte = 0x00
private const val METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
private const val CMD_CONNECT = 0x01
private const val ATYP_IPV4 = 0x01
private const val ATYP_DOMAIN = 0x03
private const val ATYP_IPV6 = 0x04
private const val REPLY_SUCCEEDED = 0x00
private const val REPLY_GENERAL_FAILURE = 0x01
private const val REPLY_NOT_ALLOWED = 0x02
private const val REPLY_CONNECTION_REFUSED = 0x05
private const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
private const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

private data class SocksTarget(val host: String, val port: Int)

/**
 * Hand-written dynamic (SOCKS5) port forward - sshj has no SOCKS server of its own. Scope is
 * narrow: the `CONNECT` command only, "no authentication" only; no BIND, no UDP ASSOCIATE, no
 * username/password auth. Address types IPv4 (0x01), domain name (0x03) and IPv6 (0x04) are
 * handled; anything else gets a correct SOCKS5 error reply and a closed connection, not a crash and
 * not silent mishandling.
 *
 * Same lifecycle shape as sshj's [net.schmizz.sshj.connection.channel.direct.LocalPortForwarder]:
 * the caller binds the [ServerSocket], so a bind failure surfaces synchronously, and [listen]
 * returns normally and not throwing when [close] closes it - the distinction
 * `SshConnection.onForwardStopped` uses to tell a stop from a failure. [close] also closes every
 * in-flight proxied [Socket], since one blocked in [pumpOneWay]'s `read()` would never notice the
 * listener had gone.
 *
 * Each client's handshake, target connect and pump run in their own child coroutine on [scope], so
 * a slow client or refused target cannot stall the accept loop.
 *
 * Nothing is resolved on this device: [readConnectRequest] builds the target string from the wire
 * bytes and passes it to SSHClient.newDirectConnection untouched, so the SSH server resolves the
 * name. That is what dynamic forwarding is for.
 */
internal class Socks5Forwarder(
    private val ssh: SSHClient,
    private val serverSocket: ServerSocket,
    private val scope: CoroutineScope,
) {
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    /** Blocking accept loop - call from a dedicated coroutine. */
    fun listen() {
        while (!serverSocket.isClosed) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (e: SocketException) {
                    if (serverSocket.isClosed) break else throw e
                }
            activeSockets += socket
            scope.launch(Dispatchers.IO) {
                try {
                    handleClient(socket)
                } catch (e: IOException) {
                    Log.d(LOG_TAG, "SOCKS5 connection ended: ${e.message}")
                } finally {
                    activeSockets -= socket
                    runCatching { socket.close() }
                }
            }
        }
    }

    /** Closes the listener and every in-flight proxied connection - see class doc. */
    fun close() {
        runCatching { serverSocket.close() }
        activeSockets.toList().forEach { runCatching { it.close() } }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        if (!negotiateNoAuth(input, output)) return
        val target = readConnectRequest(input, output) ?: return
        val channel =
            try {
                ssh.newDirectConnection(target.host, target.port).also { it.open() }
            } catch (e: IOException) {
                writeReply(output, mapOpenFailure(e))
                return
            }
        try {
            writeReply(output, REPLY_SUCCEEDED)
            // Handshake is done - an idle SSH session or long-poll must not be cut off.
            socket.soTimeout = 0
            pumpBothDirections(socket, channel)
        } finally {
            runCatching { channel.close() }
        }
    }

    /** Reads the greeting (`VER NMETHODS METHODS...`) and replies with the chosen method - `0x00` if the client offered it, else `0xFF` (per RFC 1928) and the caller closes the socket. */
    private fun negotiateNoAuth(input: DataInputStream, output: OutputStream): Boolean {
        val ver = input.readUnsignedByte()
        if (ver != SOCKS_VERSION) return false
        val methods = ByteArray(input.readUnsignedByte())
        input.readFully(methods)
        return if (methods.contains(METHOD_NO_AUTH)) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_AUTH))
            output.flush()
            true
        } else {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_ACCEPTABLE))
            output.flush()
            false
        }
    }

    /** Reads `VER CMD RSV ATYP DST.ADDR DST.PORT`; writes the matching error reply and returns `null` for anything outside this class's scope. */
    private fun readConnectRequest(input: DataInputStream, output: OutputStream): SocksTarget? {
        val ver = input.readUnsignedByte()
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // RSV - reserved, always 0x00, ignored
        val atyp = input.readUnsignedByte()
        if (ver != SOCKS_VERSION || cmd != CMD_CONNECT) {
            writeReply(output, REPLY_COMMAND_NOT_SUPPORTED)
            return null
        }
        val host =
            when (atyp) {
                ATYP_IPV4 -> ByteArray(4).also { input.readFully(it) }
                    .joinToString(".") { (it.toInt() and 0xFF).toString() }

                ATYP_DOMAIN -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    .toString(Charsets.US_ASCII)
                // getByAddress(bytes) formats raw bytes into a literal - it does not perform a
                // reverse DNS lookup (that's only getHostName()), so this stays
                // local-resolution-free.
                ATYP_IPV6 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                else -> null
            }
        if (host == null) {
            writeReply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
            return null
        }
        return SocksTarget(host, input.readUnsignedShort())
    }

    /** `BND.ADDR`/`BND.PORT` are always `0.0.0.0:0`: with no BIND support there is no meaningful bound address to report, and clients ignore it for CONNECT. */
    private fun writeReply(output: OutputStream, replyCode: Int) {
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(),
                replyCode.toByte(),
                0,
                ATYP_IPV4.toByte(),
                0,
                0,
                0,
                0,
                0,
                0
            )
        )
        output.flush()
    }

    /** [OpenFailException.Reason.CONNECT_FAILED] is the common case - the target refused the connection. */
    private fun mapOpenFailure(e: IOException): Int =
        when ((e as? OpenFailException)?.reason) {
            OpenFailException.Reason.CONNECT_FAILED -> REPLY_CONNECTION_REFUSED
            OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED -> REPLY_NOT_ALLOWED
            else -> REPLY_GENERAL_FAILURE
        }

    /** `withContext` waits for both child pumps, so this suspends until the connection is fully done. */
    private suspend fun pumpBothDirections(socket: Socket, channel: DirectConnection) {
        withContext(Dispatchers.IO) {
            launch {
                pumpOneWay(
                    socket.getInputStream(),
                    channel.outputStream
                ); runCatching { channel.close() }
            }
            launch {
                pumpOneWay(
                    channel.inputStream,
                    socket.getOutputStream()
                ); runCatching { socket.close() }
            }
        }
    }

    /** Ends quietly on EOF or either side closing mid-transfer - not a listener-level failure. */
    private fun pumpOneWay(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(PUMP_BUFFER_SIZE)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: IOException) {
            Log.d(LOG_TAG, "SOCKS5 pump ended: ${e.message}")
        }
    }
}
