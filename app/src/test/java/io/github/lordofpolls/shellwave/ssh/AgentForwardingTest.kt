package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class AgentForwardingTest {
    private val config = DefaultConfig()

    private fun newKey(algorithm: String, label: String): AgentKey {
        val pair = KeyPairGenerator.getInstance(algorithm).apply {
            when (algorithm) {
                "RSA" -> initialize(2048)
                "EC" -> initialize(ECGenParameterSpec("secp256r1"))
            }
        }.generateKeyPair()
        return AgentKey(pair.private, pair.public, label)
    }

    private val rsa = newKey("RSA", "rsa")
    private val ed25519 = newKey("Ed25519", "ed")
    private val ecdsa = newKey("EC", "ec")
    private val keys = listOf(rsa, ed25519, ecdsa)
    private val failure = byteArrayOf(5)

    private fun signRequest(blob: ByteArray, flags: Long, data: ByteArray = "payload".toByteArray()) =
        Buffer.PlainBuffer().putByte(13).putString(blob).putString(data).putUInt32(flags).compactData

    private fun answer(request: ByteArray) = answerAgentRequest(request, keys, config)

    private fun assertSigns(key: AgentKey, flags: Long, expectedFormat: String) {
        val data = "payload".toByteArray()
        val response = Buffer.PlainBuffer(answer(signRequest(key.blob, flags, data)))
        assertEquals(14, response.readByte().toInt())
        val sigBlob = response.readStringAsBytes()
        val format = Buffer.PlainBuffer(sigBlob).readString()
        val verifier = Factory.Named.Util.create(config.keyAlgorithms, format).newSignature()
        verifier.initVerify(key.publicKey)
        verifier.update(data)
        assertEquals(expectedFormat to true, format to verifier.verify(sigBlob))
    }

    private fun header(length: Int) = ByteBuffer.allocate(4).putInt(length).array()

    // Asserting "some IOException" would pass even with the cap deleted, because a short body throws
    // EOFException, which is one. Only a non-EOF IOException proves the bound itself fired.
    @Test
    fun oversizedLengthIsRefused() {
        val thrown = assertThrows(IOException::class.java) {
            readAgentFrame(ByteArrayInputStream(header(MAX_AGENT_FRAME_BYTES + 1) + 11))
        }
        assertFalse(thrown is EOFException)
    }

    @Test
    fun lengthExactlyAtTheCapIsAccepted() {
        val body = ByteArray(MAX_AGENT_FRAME_BYTES).also { it[0] = 11 }
        val frame = readAgentFrame(ByteArrayInputStream(header(MAX_AGENT_FRAME_BYTES) + body))
        assertEquals(MAX_AGENT_FRAME_BYTES, frame!!.size)
    }

    @Test
    fun cleanEofBetweenFramesIsNotAnError() {
        assertNull(readAgentFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun zeroLengthIsRefused() {
        assertThrows(IOException::class.java) { readAgentFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0))) }
    }

    @Test
    fun truncatedFrameIsRefused() {
        val frame = byteArrayOf(0, 0, 0, 10, 11, 0)
        assertThrows(EOFException::class.java) { readAgentFrame(ByteArrayInputStream(frame)) }
    }

    @Test
    fun serveLoopAnswersFailureAndStopsOnHostileLength() {
        val out = ByteArrayOutputStream()
        serveAgent(ByteArrayInputStream(byteArrayOf(0x7f, -1, -1, -1)), out, keys, config)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 5), out.toByteArray())
    }

    @Test
    fun unknownMessageTypeIsFailure() {
        assertArrayEquals(failure, answer(byteArrayOf(17)))
    }

    @Test
    fun unknownMessageTypeKeepsTheServeLoopReading() {
        val frames = ByteArrayOutputStream()
        writeAgentFrame(frames, byteArrayOf(17))
        writeAgentFrame(frames, byteArrayOf(11))
        val out = ByteArrayOutputStream()
        serveAgent(ByteArrayInputStream(frames.toByteArray()), out, keys, config)
        assertEquals(12, out.toByteArray()[4 + 1 + 4].toInt())
    }

    @Test
    fun blobOutsideTheAllowedSetIsFailure() {
        assertArrayEquals(failure, answer(signRequest(newKey("Ed25519", "other").blob, 0)))
    }

    @Test
    fun certificateBlobIsFailure() {
        val cert = Buffer.PlainBuffer().putString("ssh-ed25519-cert-v01@openssh.com").putString(ByteArray(32)).compactData
        assertArrayEquals(failure, answer(signRequest(cert, 0)))
    }

    @Test
    fun bothRsaSha2FlagsAreFailure() {
        assertArrayEquals(failure, answer(signRequest(rsa.blob, 6)))
    }

    @Test
    fun oldSignatureFlagIsFailure() {
        assertArrayEquals(failure, answer(signRequest(rsa.blob, 1)))
    }

    @Test
    fun unknownFlagBitIsFailure() {
        assertArrayEquals(failure, answer(signRequest(rsa.blob, 2 or 8)))
    }

    @Test
    fun nonZeroFlagsOnEd25519AreFailure() {
        assertArrayEquals(failure, answer(signRequest(ed25519.blob, 2)))
    }

    @Test
    fun rsaWithoutSha2FlagIsFailure() {
        assertArrayEquals(failure, answer(signRequest(rsa.blob, 0)))
    }

    @Test
    fun malformedSignRequestIsFailure() {
        assertArrayEquals(failure, answer(byteArrayOf(13, 0, 0, 0, 99)))
    }

    @Test
    fun identitiesListsExactlyTheAllowedKeysInOrder() {
        val response = Buffer.PlainBuffer(answer(byteArrayOf(11)))
        response.readByte()
        val listed = List(response.readUInt32AsInt()) { response.readStringAsBytes() to response.readString() }
        assertEquals(keys.map { it.blob.toList() to it.label }, listed.map { it.first.toList() to it.second })
    }

    @Test
    fun rsaSha2256Signs() = assertSigns(rsa, 2, "rsa-sha2-256")

    @Test
    fun rsaSha2512Signs() = assertSigns(rsa, 4, "rsa-sha2-512")

    @Test
    fun ed25519Signs() = assertSigns(ed25519, 0, "ssh-ed25519")

    // SignatureECDSA re-encodes the JCA signature from ASN.1 into `mpint r || mpint s`, which is the
    // easiest of the three encoders to get wrong and the only one with no other coverage here.
    @Test
    fun ecdsaSigns() = assertSigns(ecdsa, 0, "ecdsa-sha2-nistp256")

    @Test
    fun responseIsFramedWithItsLength() {
        val out = ByteArrayOutputStream()
        writeAgentFrame(out, failure)
        assertTrue(out.toByteArray().contentEquals(byteArrayOf(0, 0, 0, 1, 5)))
    }
}
