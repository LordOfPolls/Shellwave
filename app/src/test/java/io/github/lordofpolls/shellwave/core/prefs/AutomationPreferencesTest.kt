package io.github.lordofpolls.shellwave.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The app-wide automation gate. An exported action means any installed app can open an SSH
 * connection using stored credentials with no user interaction; this gating makes that acceptable,
 * and none of it is optional. This file is where that is enforced by something other than a
 * reviewer's memory.
 *
 * The tests that matter most are the ones asserting a refusal. A regression that opens the gate has
 * no visible symptom: everything keeps working, for everyone, including the app that should not
 * have been let in.
 */
class AutomationPreferencesTest {

    private val token = "AAAABBBBCCCCDDDDEEEEFFFF"

    @Test
    fun `a matching token on an enabled app is allowed`() {
        assertNull(automationRefusal(enabled = true, storedToken = token, providedToken = token))
    }

    /** The default state of a fresh install, and the one an outside app hits before the user has agreed to anything. */
    @Test
    fun `the right token is still refused while the switch is off`() {
        assertNotNull(
            automationRefusal(
                enabled = false,
                storedToken = token,
                providedToken = token
            )
        )
    }

    /**
     * Belt and braces against the state the UI is not supposed to be able to produce. If enabling ever
     * stopped minting a token, an empty stored token compared against an empty provided one would
     * otherwise be a match; the switch would be on and every app would hold the key.
     */
    @Test
    fun `an enabled app with no token generated refuses`() {
        assertNotNull(automationRefusal(enabled = true, storedToken = null, providedToken = null))
        assertNotNull(automationRefusal(enabled = true, storedToken = "", providedToken = ""))
    }

    @Test
    fun `a request with no token is refused`() {
        assertNotNull(automationRefusal(enabled = true, storedToken = token, providedToken = null))
        assertNotNull(automationRefusal(enabled = true, storedToken = token, providedToken = ""))
    }

    @Test
    fun `a wrong token is refused`() {
        assertNotNull(
            automationRefusal(
                enabled = true,
                storedToken = token,
                providedToken = "AAAABBBBCCCCDDDDEEEEFFFX"
            )
        )
    }

    /** A prefix is what a token guessed one character at a time looks like on its way in. */
    @Test
    fun `a token that is only a prefix of the real one is refused`() {
        assertNotNull(
            automationRefusal(
                enabled = true,
                storedToken = token,
                providedToken = token.dropLast(1)
            )
        )
        assertNotNull(
            automationRefusal(
                enabled = true,
                storedToken = token,
                providedToken = token + "X"
            )
        )
    }

    /** Tasker pastes are whitespace-prone and Base64 is case-significant; neither is a reason to accept a near miss. */
    @Test
    fun `a token differing only in case or whitespace is still a wrong token`() {
        assertNotNull(
            automationRefusal(
                enabled = true,
                storedToken = token,
                providedToken = token.lowercase()
            )
        )
        assertNotNull(
            automationRefusal(
                enabled = true,
                storedToken = token,
                providedToken = " $token"
            )
        )
    }

    @Test
    fun `every refusal says which gate stopped it and where to fix it`() {
        val refusals =
            listOf(
                automationRefusal(enabled = false, storedToken = token, providedToken = token),
                automationRefusal(enabled = true, storedToken = token, providedToken = null),
                automationRefusal(enabled = true, storedToken = token, providedToken = "wrong"),
            )

        refusals.forEach { message ->
            assertNotNull(message)
            assertTrue("must point somewhere actionable: $message", message!!.contains("Settings"))
        }
    }

    /**
     * Not a style check. A token is only worth anything if it is unguessable, and the two ways this
     * silently stops being true are a fixed seed and a shortened length - both of which leave a string
     * that still looks exactly like a token.
     */
    @Test
    fun `a generated token is long, URL-safe, and different every time`() {
        val first = newAutomationToken()
        val second = newAutomationToken()

        assertNotEquals(first, second)
        // 24 bytes of Base64 without padding.
        assertEquals(32, first.length)
        assertTrue(
            "must survive a shell and a Tasker extra unquoted: $first",
            first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    /** [SecureRandom] is the contract, so the generator has to consume it, beyond merely accepting it. */
    @Test
    fun `the generator draws from the random source it is given`() {
        val fixed =
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) = bytes.fill(7)
            }

        assertEquals(newAutomationToken(fixed), newAutomationToken(fixed))
        assertNotEquals(newAutomationToken(fixed), newAutomationToken())
    }
}
