package io.github.lordofpolls.shellwave.feature.keys

import io.github.lordofpolls.shellwave.ssh.PuttyKeyFixtures
import io.github.lordofpolls.shellwave.ssh.publicKeyLineOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyImportErrorTest {

    @Test
    fun `a wrong passphrase says what to do and keeps sshj's wording`() {
        val ppk = PuttyKeyFixtures.ppk(PuttyKeyFixtures.rsaKeyPair(), version = 2, passphrase = "right")

        val failure = assertThrows(Exception::class.java) { publicKeyLineOf(ppk, "wrong") }

        val message = keyImportErrorMessage(failure)
        assertTrue(message, message.startsWith("Wrong passphrase"))
        assertTrue(message, message.endsWith("[${failure.message}]"))
    }

    @Test
    fun `text that is not a key file says so`() {
        val failure = assertThrows(Exception::class.java) { publicKeyLineOf("hello", null) }

        val message = keyImportErrorMessage(failure)
        assertTrue(message, message.startsWith("That isn't a private key file"))
        assertTrue(message, message.endsWith("[${failure.message}]"))
    }

    @Test
    fun `anything else passes through untouched`() {
        assertEquals("disk on fire", keyImportErrorMessage(RuntimeException("disk on fire")))
        assertEquals("Could not store the key", keyImportErrorMessage(RuntimeException()))
    }
}
