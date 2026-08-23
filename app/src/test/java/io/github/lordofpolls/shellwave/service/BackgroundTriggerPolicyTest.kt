package io.github.lordofpolls.shellwave.service

import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background-trigger refusals, pinned. Each exists because a widget tap has no UI to answer a
 * prompt with, and if one stops holding the failure is not a crash or a wrong pixel but a command
 * running somewhere the user never chose. Rules that have to fail closed get a test over a comment.
 *
 * These assert the decision and not the wording, except where a message that does not say what to
 * do instead would leave the user stuck.
 */
class BackgroundTriggerPolicyTest {

    private fun script(
        mode: String = "CAPTURE",
        targetHostId: Long? = 1,
        snippet: String = "df -h",
        allowAutomation: Boolean = false
    ) =
        ScriptEntity(
            id = 1,
            name = "Disk usage",
            icon = null,
            color = null,
            targetHostId = targetHostId,
            snippet = snippet,
            mode = mode,
            disconnectAfter = false,
            paramsJson = "",
            confirmBeforeRun = false,
            createdAt = 0,
            allowAutomation = allowAutomation,
        )

    @Test
    fun `a capture script with a fixed host and no params is allowed`() {
        assertNull(backgroundTriggerRefusal(script()))
    }

    @Test
    fun `attach is refused`() {
        assertNotNull(backgroundTriggerRefusal(script(mode = "ATTACH")))
    }

    @Test
    fun `send-to-current is refused`() {
        assertNotNull(backgroundTriggerRefusal(script(mode = "SEND_TO_CURRENT")))
    }

    /** Decoding is tolerant everywhere else in this feature; here tolerance has to mean "refuse", not "assume capture". */
    @Test
    fun `an unrecognised stored mode is refused`() {
        assertNotNull(backgroundTriggerRefusal(script(mode = "FUTURE_MODE")))
    }

    /** Not run with blank substitutions: `rm -rf /home/` is what a guessed-empty `{{dir}}` looks like. */
    @Test
    fun `a script with a param placeholder is refused`() {
        assertNotNull(backgroundTriggerRefusal(script(snippet = "rm -rf /home/{{dir}}")))
    }

    /**
     * "Ask each run" is a legitimate saved state, which makes this the one refusal here that guards a
     * script the user created instead of a mismatch.
     */
    @Test
    fun `a script with no fixed target host is refused`() {
        assertNotNull(backgroundTriggerRefusal(script(targetHostId = null)))
    }

    @Test
    fun `every refusal points at the app rather than just saying no`() {
        val refusals =
            listOf(
                script(mode = "ATTACH"),
                script(snippet = "echo {{value}}"),
                script(targetHostId = null),
            ).map { backgroundTriggerRefusal(it) }

        refusals.forEach { message ->
            assertNotNull(message)
            assertTrue(
                "must name the app as the way out: $message",
                message!!.contains("Shellwave")
            )
        }
    }

    /**
     * Order matters for the message as well as the verdict: a script that is wrong in more than one way
     * should be described by the first thing that stops it, so fixing that one reveals the next and not
     * the user seeing an arbitrary pick.
     */
    @Test
    fun `mode is reported before the missing host when a script fails both`() {
        val message = backgroundTriggerRefusal(script(mode = "ATTACH", targetHostId = null))

        assertTrue(
            "expected the mode refusal: $message",
            message.orEmpty().contains("capture-mode")
        )
    }

    // These are the tests that decide whether an exported action is acceptable, so they check the
    // default direction as well as the toggle: the dangerous failure is not "a refusal is worded
    // oddly", it is "allowAutomation stopped being consulted" - which a test that only ever passes
    // `allowAutomation = true` would not notice.

    @Test
    fun `a widget trigger is unaffected by allowAutomation`() {
        assertNull(backgroundTriggerRefusal(script(allowAutomation = false)))
    }

    @Test
    fun `an automation trigger is refused for a script that has not opted in`() {
        assertNotNull(
            backgroundTriggerRefusal(
                script(allowAutomation = false),
                fromAutomation = true
            )
        )
    }

    @Test
    fun `an automation trigger is allowed for a script that has opted in`() {
        assertNull(backgroundTriggerRefusal(script(allowAutomation = true), fromAutomation = true))
    }

    /**
     * The shape of the design: opting a script in to automation grants it one extra door, it does not
     * lift the refusals every background trigger already obeys. If this ever passes, an outside app can
     * run an ATTACH script, a parameterised script, or a script with no chosen host.
     */
    @Test
    fun `opting in to automation lifts none of the widget's refusals`() {
        assertNotNull(
            backgroundTriggerRefusal(
                script(mode = "ATTACH", allowAutomation = true),
                fromAutomation = true
            )
        )
        assertNotNull(
            backgroundTriggerRefusal(
                script(
                    snippet = "echo {{value}}",
                    allowAutomation = true
                ), fromAutomation = true
            )
        )
        assertNotNull(
            backgroundTriggerRefusal(
                script(targetHostId = null, allowAutomation = true),
                fromAutomation = true
            )
        )
    }

    /** The coarsest gate is reported first: telling a caller that a script it was never entitled to name is the wrong mode confirms a detail about a script it has no business knowing. */
    @Test
    fun `the automation refusal is reported first`() {
        val message = backgroundTriggerRefusal(
            script(mode = "ATTACH", targetHostId = null),
            fromAutomation = true
        )

        assertTrue(
            "expected the automation refusal: $message",
            message.orEmpty().contains("not available to other apps")
        )
    }
}
