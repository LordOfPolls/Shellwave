package io.github.lordofpolls.shellwave.core.net

import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityInterval
import io.github.lordofpolls.shellwave.ui.design.reachabilityDescription
import io.github.lordofpolls.shellwave.ui.design.reachabilityWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the reachability indicator that can be decided without a socket. The probe loop
 * itself is lifecycle- and network-bound and belongs on a device; what is pinned here is everything
 * that would make the indicator lie.
 */
class HostReachabilityTest {

    private fun host(id: Long, proxyJumpHostId: Long? = null) =
        HostEntity(
            id = id,
            label = "host-$id",
            hostname = "10.0.0.$id",
            port = 22,
            username = "polls",
            credentialId = 1,
            lastConnectedAt = null,
            createdAt = 0,
            proxyJumpHostId = proxyJumpHostId,
        )

    /**
     * The failure this prevents is subtle and self-inflicted: a proxied host is unreachable from the
     * phone by design, so probing it directly would mark a perfectly healthy host DOWN on every single
     * pass - and an indicator that is reliably wrong about one host teaches the user to stop reading it
     * about all of them.
     */
    @Test
    fun `hosts behind a jump host are never probed`() {
        val direct = host(1)
        val proxied = host(2, proxyJumpHostId = 1)

        assertEquals(listOf(direct), hostsToProbe(listOf(direct, proxied)))
    }

    @Test
    fun `hosts with no jump host are all probed`() {
        val hosts = listOf(host(1), host(2), host(3))

        assertEquals(hosts, hostsToProbe(hosts))
    }

    @Test
    fun `an empty host list probes nothing rather than throwing`() {
        assertEquals(emptyList<HostEntity>(), hostsToProbe(emptyList()))
    }

    /** The fixed status vocabulary is uppercase; UNKNOWN is a dash, because it is the absence of a reading instead of a third one. */
    @Test
    fun `the vocabulary is UP DOWN and a dash`() {
        assertEquals("UP", reachabilityWord(Reachability.UP))
        assertEquals("DOWN", reachabilityWord(Reachability.DOWN))
        assertEquals("—", reachabilityWord(Reachability.UNKNOWN))
    }

    /**
     * A screen reader cannot usefully say "-", and "UP" alone is jargon read aloud - so the spoken form
     * is words. It also stays honest about what was actually learned: a TCP connect succeeded, which is
     * not the same claim as "this machine is healthy".
     */
    @Test
    fun `every state has a spoken form that does not overclaim`() {
        Reachability.entries.forEach { state ->
            val spoken = reachabilityDescription(state)
            assertTrue("$state has no spoken form", spoken.isNotBlank())
            assertTrue(
                "$state should not be described as online: $spoken",
                !spoken.contains("online", ignoreCase = true)
            )
        }
        assertEquals("Reachable", reachabilityDescription(Reachability.UP))
        assertEquals("Not reachable", reachabilityDescription(Reachability.DOWN))
    }

    /**
     * A pass must finish inside its own interval, or probes pile up on each other. The 3s connect
     * timeout is well under the shortest option, but the shortest option is the one that could be
     * shortened later without anyone rechecking the timeout - so the relationship is asserted and not
     * left as arithmetic in a comment.
     */
    @Test
    fun `the shortest interval exceeds one probe timeout`() {
        val shortest = ReachabilityInterval.entries.minOf { it.millis }

        assertTrue("shortest interval is ${shortest}ms", shortest >= 10_000)
    }

    @Test
    fun `intervals are ordered shortest to longest`() {
        val millis = ReachabilityInterval.entries.map { it.millis }

        assertEquals(millis.sorted(), millis)
    }
}
