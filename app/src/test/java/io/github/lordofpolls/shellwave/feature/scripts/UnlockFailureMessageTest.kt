package io.github.lordofpolls.shellwave.feature.scripts

import io.github.lordofpolls.shellwave.core.crypto.BiometricAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

class UnlockFailureMessageTest {
    @Test
    fun `the app's own refusals and biometric errors pass through`() {
        assertEquals("Edit the host.", unlockFailureMessage(IllegalStateException("Edit the host.")))
        assertEquals("Too many attempts", unlockFailureMessage(BiometricAuthException(7, "Too many attempts")))
    }

    @Test
    fun `a keystore message gets a sentence that says what to do`() {
        val message = unlockFailureMessage(GeneralSecurityException("Key permanently invalidated"))
        assertTrue(message.startsWith("Couldn't unlock this host's saved credential. Edit the host"))
        assertTrue(message.endsWith("(Key permanently invalidated)"))
    }
}
