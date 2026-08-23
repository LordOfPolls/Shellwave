package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** [describeConnectFailure] is as much about what it leaves alone as what it rewrites. */
class ConnectFailureTest {
    private val password = AuthMethod.Password("hunter2")
    private val key = AuthMethod.PrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----", null)

    @Test
    fun exhaustedMethods_isRewritten() {
        val message = describeConnectFailure(
            UserAuthException("Exhausted available authentication methods"),
            password
        )

        assertEquals(
            "Authentication failed - the server rejected that username or password.",
            message
        )
        assertFalse(
            "sshj's wording must not survive into user-visible text",
            message.contains("Exhausted")
        )
    }

    @Test
    fun exhaustedMethods_namesTheCredentialUsed() {
        val exhausted = UserAuthException("Exhausted available authentication methods")

        assertTrue(describeConnectFailure(exhausted, key).contains("username or key"))
        assertTrue(describeConnectFailure(exhausted, password).contains("username or password"))
    }

    /** A wrong username and a wrong password look identical from here; the server will not say which. */
    @Test
    fun exhaustedMethods_doesNotBlameThePasswordAlone() {
        val message = describeConnectFailure(
            UserAuthException("Exhausted available authentication methods"),
            password
        )

        assertTrue(message.contains("username or password"))
    }

    @Test
    fun otherAuthFailures_passThroughVerbatim() {
        val specific = UserAuthException("Could not decrypt the private key - wrong passphrase?")

        assertEquals(
            "Could not decrypt the private key - wrong passphrase?",
            describeConnectFailure(specific, key)
        )
    }

    /** An sshj upgrade that rewords the message degrades to today's behaviour rather than to a wrong claim. */
    @Test
    fun anUnrecognisedAuthMessage_isNotRewritten() {
        val rephrased = UserAuthException("No more authentication methods available")

        assertEquals(
            "No more authentication methods available",
            describeConnectFailure(rephrased, password)
        )
    }

    @Test
    fun transportFailures_gainASubject() {
        assertTrue(
            describeConnectFailure(
                ConnectException("Connection refused"),
                password
            ).contains("host and port")
        )
        assertTrue(
            describeConnectFailure(
                UnknownHostException("nope.invalid"),
                password
            ).contains("did not resolve")
        )
        assertTrue(
            describeConnectFailure(
                SocketTimeoutException("Read timed out"),
                password
            ).contains("did not respond in time")
        )
    }

    @Test
    fun anExceptionWithNoMessage_doesNotRenderNull() {
        assertEquals("Connection failed", describeConnectFailure(RuntimeException(), password))
    }
}
