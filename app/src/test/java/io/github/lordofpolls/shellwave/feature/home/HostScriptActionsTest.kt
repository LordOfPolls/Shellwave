package io.github.lordofpolls.shellwave.feature.home

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which scripts a host card offers, and which host they run against.
 *
 * The filter carries a real risk in both directions. Too narrow and a reusable script is invisible
 * exactly where it is most useful; too wide and a `SEND_TO_CURRENT` script appears in a host's
 * menu, promising to run somewhere it will not.
 */
class HostScriptActionsTest {

    private fun host(id: Long, label: String) =
        HostEntity(
            id = id,
            label = label,
            hostname = "10.0.0.$id",
            port = 22,
            username = "polls",
            credentialId = 1,
            lastConnectedAt = null,
            createdAt = 0,
        )

    private fun script(id: Long, name: String, targetHostId: Long?, mode: String = "CAPTURE") =
        ScriptEntity(
            id = id,
            name = name,
            icon = null,
            color = null,
            targetHostId = targetHostId,
            snippet = "df -h",
            mode = mode,
            disconnectAfter = false,
            paramsJson = "",
            confirmBeforeRun = false,
            createdAt = 0,
        )

    private val betty = host(1, "betty")
    private val gamma = host(2, "gamma")

    private fun namesFor(host: HostEntity, scripts: List<ScriptEntity>): List<String> =
        hostScriptActions(host, scripts) { _, _ -> }.map { it.name }

    @Test
    fun `a script targeting this host is offered`() {
        assertEquals(listOf("Disk"), namesFor(betty, listOf(script(1, "Disk", betty.id))))
    }

    @Test
    fun `a script targeting another host is not`() {
        assertEquals(emptyList<String>(), namesFor(betty, listOf(script(1, "Disk", gamma.id))))
    }

    /** "Ask each run" means every card, because every host is a legitimate answer. */
    @Test
    fun `a host-agnostic script is offered on every card`() {
        val scripts = listOf(script(1, "Uptime", null))

        assertEquals(listOf("Uptime"), namesFor(betty, scripts))
        assertEquals(listOf("Uptime"), namesFor(gamma, scripts))
    }

    /**
     * A null target does not mean "offer everywhere" on its own - SEND_TO_CURRENT is null for a
     * different reason (it targets a session), and would be lying about where it runs.
     */
    @Test
    fun `a send-to-current script is excluded even with no target host`() {
        assertEquals(
            emptyList<String>(),
            namesFor(betty, listOf(script(1, "Tail", null, mode = "SEND_TO_CURRENT")))
        )
    }

    /**
     * What offering a reusable script here buys: the card answers the host question, so running from it
     * must not stop to ask again.
     */
    @Test
    fun `running a host-agnostic script from a card passes that card's host`() {
        var ran: Pair<ScriptEntity, HostEntity>? = null
        val actions =
            hostScriptActions(gamma, listOf(script(1, "Uptime", null))) { s, h -> ran = s to h }

        actions.single().onRun()

        assertEquals(gamma, ran?.second)
    }

    @Test
    fun `running a host-targeted script passes that same host`() {
        var ran: Pair<ScriptEntity, HostEntity>? = null
        val actions =
            hostScriptActions(betty, listOf(script(1, "Disk", betty.id))) { s, h -> ran = s to h }

        actions.single().onRun()

        assertEquals(betty, ran?.second)
    }

    /** Tolerant decoding again: an unrecognised mode is not SEND_TO_CURRENT, so it stays visible rather than vanishing from a menu. */
    @Test
    fun `an unrecognised mode is still offered`() {
        assertEquals(
            listOf("Disk"),
            namesFor(betty, listOf(script(1, "Disk", betty.id, mode = "FUTURE_MODE")))
        )
    }
}
