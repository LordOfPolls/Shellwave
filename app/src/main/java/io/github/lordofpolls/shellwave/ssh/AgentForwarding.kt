package io.github.lordofpolls.shellwave.ssh

import android.util.Log
import net.schmizz.sshj.Config
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannel
import net.schmizz.sshj.connection.channel.forwarded.ForwardedChannelOpener
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "AgentForwarding"
private const val AGENT_CHANNEL_TYPE = "auth-agent@openssh.com"
private const val AGENT_REQUEST = "auth-agent-req@openssh.com"
private const val MAX_AGENT_CHANNELS = 4

/** Sentinel for "the open packet never yielded a recipient", so no open failure can be addressed. */
private const val NO_RECIPIENT = -1

internal const val MAX_AGENT_FRAME_BYTES = 32 * 1024

private const val FAILURE: Byte = 5
private const val REQUEST_IDENTITIES: Byte = 11
private const val IDENTITIES_ANSWER: Byte = 12
private const val SIGN_REQUEST: Byte = 13
private const val SIGN_RESPONSE: Byte = 14

private const val RSA_SHA2_256 = 2L
private const val RSA_SHA2_512 = 4L

/** A fresh array each time: a shared one would be process-wide mutable state handed to callers. */
private fun failurePayload() = byteArrayOf(FAILURE)

/** Holds parsed key objects only, so no PEM outlives the resolve step. */
internal class AgentKey(val privateKey: PrivateKey, val publicKey: PublicKey, val label: String) {
    val blob: ByteArray = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
    val keyType: KeyType = KeyType.fromKey(publicKey)
}

/** Total: any failure, including malformed input, comes back as `SSH_AGENT_FAILURE`. */
internal fun answerAgentRequest(request: ByteArray, keys: List<AgentKey>, config: Config): ByteArray =
    try {
        when (request.firstOrNull()) {
            REQUEST_IDENTITIES -> identitiesAnswer(keys)
            SIGN_REQUEST -> signResponse(request, keys, config)
            else -> failurePayload()
        }
    } catch (e: Exception) {
        Log.d(LOG_TAG, "agent request refused: ${e.javaClass.simpleName}")
        failurePayload()
    }

private fun identitiesAnswer(keys: List<AgentKey>): ByteArray {
    val out = Buffer.PlainBuffer().putByte(IDENTITIES_ANSWER).putUInt32(keys.size.toLong())
    keys.forEach { out.putString(it.blob).putString(it.label) }
    return out.compactData
}

private fun signResponse(request: ByteArray, keys: List<AgentKey>, config: Config): ByteArray {
    val buf = Buffer.PlainBuffer(request)
    buf.readByte()
    val requestedBlob = buf.readStringAsBytes()
    // Deliberately any payload, not just userauth requests: remote git commit signing needs it.
    // See docs/agent-forwarding-spec.md "Signing scope" before adding a restriction here.
    val data = buf.readStringAsBytes()
    val flags = buf.readUInt32()

    val key = keys.firstOrNull { it.blob.contentEquals(requestedBlob) } ?: return failurePayload()
    val algorithm = signatureAlgorithm(key, flags) ?: return failurePayload()
    val keyAlgorithm = Factory.Named.Util.create(config.keyAlgorithms, algorithm) ?: return failurePayload()
    if (keyAlgorithm.keyFormat != key.keyType) return failurePayload()

    val signature = keyAlgorithm.newSignature()
    signature.initSign(key.privateKey)
    signature.update(data)
    val encoded = signature.encode(signature.sign())
    // Both branches already yield the length-prefixed `string sigBlob`; wrapping it again would double it.
    val sigBlob =
        if (signature.isSignaturePreEncoded()) {
            Buffer.PlainBuffer().putString(encoded).compactData
        } else {
            Buffer.PlainBuffer().putSignature(signature.signatureName, encoded).compactData
        }
    return byteArrayOf(SIGN_RESPONSE) + sigBlob
}

/** Plain SHA-1 `ssh-rsa` is refused: a server asking for it is steering us to a weaker hash. */
private fun signatureAlgorithm(key: AgentKey, flags: Long): String? {
    if ((flags and (RSA_SHA2_256 or RSA_SHA2_512).inv()) != 0L) return null
    if (key.keyType != KeyType.RSA) return if (flags == 0L) key.keyType.toString() else null
    return when (flags) {
        RSA_SHA2_256 -> "rsa-sha2-256"
        RSA_SHA2_512 -> "rsa-sha2-512"
        else -> null
    }
}

/** Null on clean EOF between frames. Throws on a bad length or a frame cut short. */
internal fun readAgentFrame(input: InputStream): ByteArray? {
    val first = input.read()
    if (first < 0) return null
    val data = DataInputStream(input)
    val header = ByteArray(4).also { it[0] = first.toByte() }
    data.readFully(header, 1, 3)
    val length = ByteBuffer.wrap(header).int
    // Checked before allocating: the length is attacker-controlled.
    if (length <= 0 || length > MAX_AGENT_FRAME_BYTES) throw IOException("agent frame length $length")
    return ByteArray(length).also { data.readFully(it) }
}

internal fun writeAgentFrame(output: OutputStream, payload: ByteArray) {
    output.write(ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array())
    output.flush()
}

/**
 * Blocking. Returns on EOF or after a protocol/IO failure; the caller closes the channel. Throwing
 * here would crash the app from a coroutine nothing supervises, so every [Exception] ends in a
 * best-effort `SSH_AGENT_FAILURE` instead.
 */
internal fun serveAgent(input: InputStream, output: OutputStream, keys: List<AgentKey>, config: Config) {
    try {
        while (true) {
            val request = readAgentFrame(input) ?: return
            writeAgentFrame(output, answerAgentRequest(request, keys, config))
        }
    } catch (e: Exception) {
        if (e !is EOFException) Log.d(LOG_TAG, "agent channel ended: ${e.javaClass.simpleName}")
        runCatching { writeAgentFrame(output, failurePayload()) }
    }
}

/** `open()` -> `allocatePTY` -> [requestAgentForwarding] -> `startShell()`; a later request trips `checkReuse`. */
internal class AgentForwardingSession(connection: Connection, remoteCharset: Charset) :
    SessionChannel(connection, remoteCharset) {
    /**
     * Throws when the server refuses, which `AllowAgentForwarding no` does routinely. The caller
     * must catch and carry on without forwarding - letting this escape would turn the per-host
     * toggle into "this host no longer connects".
     */
    fun requestAgentForwarding() {
        sendChannelRequest(AGENT_REQUEST, true, null).await(conn.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }
}

internal class AgentChannel(
    connection: Connection,
    recipient: Int,
    remoteWindow: Long,
    remoteMaxPacket: Long,
    private val onFinished: (AgentChannel) -> Unit,
) : AbstractForwardedChannel(connection, AGENT_CHANNEL_TYPE, recipient, remoteWindow, remoteMaxPacket, "", 0) {
    override fun finishOff() {
        onFinished(this)
        super.finishOff()
    }
}

/**
 * [handleOpen] runs on sshj's single transport reader thread, so it only parses, counts and hands
 * off. [handOff] must return promptly and must not block.
 *
 * The open message for this type carries no type-specific data, so unlike `X11Forwarder` nothing
 * is read after the three standard fields; reading more would kill the transport.
 */
internal class AgentChannelOpener(
    private val connection: Connection,
    private val handOff: (AgentChannel) -> Unit,
) : ForwardedChannelOpener {
    private val live = ConcurrentHashMap.newKeySet<AgentChannel>()

    override fun getChannelType(): String = AGENT_CHANNEL_TYPE

    override fun handleOpen(buf: SSHPacket) {
        var recipient = NO_RECIPIENT
        var channel: AgentChannel? = null
        runCatching {
            recipient = buf.readUInt32AsInt()
            val window = buf.readUInt32()
            val maxPacket = buf.readUInt32()
            val opened = AgentChannel(connection, recipient, window, maxPacket) { live.remove(it) }
            channel = opened
            if (live.size >= MAX_AGENT_CHANNELS) {
                opened.reject(OpenFailException.Reason.RESOURCE_SHORTAGE, "")
            } else {
                live.add(opened)
                // Hand off before confirming, and keep it that way: `confirm()` is what attaches the
                // channel, so a throw after it would strand an attached channel nobody serves. An
                // open failure sent after a confirmation is the worse protocol violation.
                handOff(opened)
                opened.confirm()
            }
        }.onFailure {
            Log.d(LOG_TAG, "agent channel open failed: ${it.javaClass.simpleName}")
            channel?.let(live::remove)
            if (recipient != NO_RECIPIENT) {
                runCatching {
                    connection.sendOpenFailure(recipient, OpenFailException.Reason.CONNECT_FAILED, "")
                }
            }
        }
    }
}
