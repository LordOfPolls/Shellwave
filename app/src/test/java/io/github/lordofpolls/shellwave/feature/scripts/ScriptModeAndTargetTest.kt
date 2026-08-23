package io.github.lordofpolls.shellwave.feature.scripts

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [scriptModeAndTarget] builds a script row's machine meta line. The cases worth pinning are the
 * ones where a separator would otherwise dangle (no target host) and the one where a stored value
 * isn't a known mode - this feature decodes modes tolerantly everywhere else, and the list must not
 * be the one place a hand-edited row throws.
 */
class ScriptModeAndTargetTest {
    private fun host(id: Long, label: String?, hostname: String) =
        HostEntity(
            id = id,
            label = label,
            hostname = hostname,
            port = 22,
            username = "polls",
            credentialId = 1,
            lastConnectedAt = null,
            createdAt = 0,
        )

    private fun script(mode: String, targetHostId: Long?) =
        ScriptEntity(
            id = 1,
            name = "Disk usage",
            icon = null,
            color = null,
            targetHostId = targetHostId,
            snippet = "df -h",
            mode = mode,
            disconnectAfter = true,
            paramsJson = "",
            confirmBeforeRun = false,
            createdAt = 0,
        )

    @Test
    fun `capture script with a labelled host reads mode then host label`() {
        val hosts = listOf(host(1, "betty", "10.0.0.5"))
        assertEquals("CAPTURE · betty", scriptModeAndTarget(script("CAPTURE", 1), hosts))
    }

    @Test
    fun `host without a label falls back to its hostname`() {
        val hosts = listOf(host(1, null, "10.0.0.5"))
        assertEquals("ATTACH · 10.0.0.5", scriptModeAndTarget(script("ATTACH", 1), hosts))
    }

    /** SEND_TO_CURRENT has no fixed host by definition - the line must not end in a dangling separator. */
    @Test
    fun `script with no target host shows its mode alone`() {
        assertEquals(
            "SEND_TO_CURRENT",
            scriptModeAndTarget(script("SEND_TO_CURRENT", null), emptyList())
        )
    }

    /**
     * For the two modes that do take a host, a missing one is a stated choice rather than an omission,
     * and the row has to say which: otherwise a reusable script and a session-typing one are
     * indistinguishable at a glance.
     */
    @Test
    fun `a host-agnostic script says it asks each run`() {
        assertEquals(
            "CAPTURE · Ask each run",
            scriptModeAndTarget(script("CAPTURE", null), emptyList())
        )
        assertEquals(
            "ATTACH · Ask each run",
            scriptModeAndTarget(script("ATTACH", null), emptyList())
        )
    }

    /** A targetHostId whose row was deleted resolves to nothing, and must degrade the same way. */
    @Test
    fun `unresolvable target host shows the mode alone`() {
        assertEquals(
            "CAPTURE",
            scriptModeAndTarget(script("CAPTURE", 99), listOf(host(1, "betty", "10.0.0.5")))
        )
    }

    @Test
    fun `an unrecognised stored mode renders verbatim`() {
        assertEquals("FUTURE_MODE", scriptModeAndTarget(script("FUTURE_MODE", null), emptyList()))
    }
}
