package io.github.lordofpolls.shellwave.feature.home

import io.github.lordofpolls.shellwave.core.db.entities.GroupEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.prefs.HostSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Covers [buildSections] and the pure helpers behind the grouped Hosts list. */
class HostGroupsTest {

    private fun host(
        id: Long,
        groupId: Long? = null,
        lastConnectedAt: Long? = null,
        connectCount: Int = 0,
        position: Int? = null,
    ) =
        HostEntity(
            id = id,
            label = "h$id",
            hostname = "10.0.0.$id",
            port = 22,
            username = "alice",
            credentialId = 1L,
            lastConnectedAt = lastConnectedAt,
            createdAt = 0L,
            groupId = groupId,
            position = position,
            connectCount = connectCount,
        )

    private fun group(id: Long, position: Int? = null, color: Int = 0) =
        GroupEntity(id = id, name = "g$id", color = color, position = position)

    private fun ids(section: HostSection) = section.hosts.map { it.id }

    @Test
    fun `ungrouped block comes last`() {
        val sections = buildSections(listOf(host(1, groupId = 10), host(2)), listOf(group(10)), HostSort.ALPHABETICAL)
        assertEquals(10L, sections[0].group?.id)
        assertNull(sections[1].group)
        assertEquals(listOf(2L), ids(sections[1]))
    }

    @Test
    fun `ungrouped block is omitted when empty`() {
        val sections = buildSections(listOf(host(1, groupId = 10)), listOf(group(10)), HostSort.ALPHABETICAL)
        assertEquals(listOf<Long?>(10L), sections.map { it.group?.id })
    }

    @Test
    fun `no groups gives the flat list`() {
        val sections = buildSections(listOf(host(1), host(2)), emptyList(), HostSort.ALPHABETICAL)
        assertEquals(1, sections.size)
        assertEquals(listOf(1L, 2L), ids(sections[0]))
    }

    @Test
    fun `dangling groupId falls into the ungrouped block`() {
        val sections = buildSections(listOf(host(1, groupId = 99)), listOf(group(10)), HostSort.ALPHABETICAL)
        assertNull(sections[1].group)
        assertEquals(listOf(1L), ids(sections[1]))
    }

    @Test
    fun `empty groups still get a section`() {
        val sections = buildSections(emptyList(), listOf(group(10), group(11)), HostSort.ALPHABETICAL)
        assertEquals(listOf<Long?>(10L, 11L), sections.map { it.group?.id })
        assertEquals(true, sections.all { it.hosts.isEmpty() })
    }

    @Test
    fun `alphabetical keeps the order given`() {
        val sections = buildSections(listOf(host(3), host(1), host(2)), emptyList(), HostSort.ALPHABETICAL)
        assertEquals(listOf(3L, 1L, 2L), ids(sections[0]))
    }

    @Test
    fun `recent puts newest first and never-connected last in given order`() {
        val hosts = listOf(host(1), host(2, lastConnectedAt = 100), host(3), host(4, lastConnectedAt = 200))
        val sections = buildSections(hosts, emptyList(), HostSort.RECENT)
        assertEquals(listOf(4L, 2L, 1L, 3L), ids(sections[0]))
    }

    @Test
    fun `recent breaks ties alphabetically`() {
        val hosts = listOf(host(1, lastConnectedAt = 100), host(2, lastConnectedAt = 100))
        assertEquals(listOf(1L, 2L), ids(buildSections(hosts, emptyList(), HostSort.RECENT)[0]))
    }

    @Test
    fun `most used puts highest first and breaks ties alphabetically`() {
        val hosts = listOf(host(1, connectCount = 2), host(2, connectCount = 5), host(3, connectCount = 2))
        val sections = buildSections(hosts, emptyList(), HostSort.MOST_USED)
        assertEquals(listOf(2L, 1L, 3L), ids(sections[0]))
    }

    @Test
    fun `manual orders by position with unseeded hosts last in given order`() {
        val hosts = listOf(host(1), host(2, position = 1), host(3, position = 0), host(4))
        val sections = buildSections(hosts, emptyList(), HostSort.MANUAL)
        assertEquals(listOf(3L, 2L, 1L, 4L), ids(sections[0]))
    }

    @Test
    fun `manual breaks duplicate positions alphabetically`() {
        val hosts = listOf(host(1, position = 0), host(2, position = 0))
        assertEquals(listOf(1L, 2L), ids(buildSections(hosts, emptyList(), HostSort.MANUAL)[0]))
    }

    @Test
    fun `headers reorder only in manual`() {
        val groups = listOf(group(10, position = 1), group(11, position = 0), group(12))
        val others = listOf(HostSort.ALPHABETICAL, HostSort.RECENT, HostSort.MOST_USED)
        others.forEach { sort ->
            assertEquals(listOf<Long?>(10L, 11L, 12L), buildSections(emptyList(), groups, sort).map { it.group?.id })
        }
        assertEquals(
            listOf<Long?>(11L, 10L, 12L),
            buildSections(emptyList(), groups, HostSort.MANUAL).map { it.group?.id },
        )
    }

    @Test
    fun `sorting applies inside each group`() {
        val hosts = listOf(host(1, groupId = 10, connectCount = 1), host(2, groupId = 10, connectCount = 9))
        val sections = buildSections(hosts, listOf(group(10)), HostSort.MOST_USED)
        assertEquals(listOf(2L, 1L), ids(sections[0]))
    }

    @Test
    fun `positionsFor numbers from the order given`() {
        assertEquals(mapOf(7L to 0, 3L to 1, 9L to 2), positionsFor(listOf(7, 3, 9)))
    }

    @Test
    fun `swapped exchanges with the neighbour`() {
        assertEquals(listOf(2L, 1L, 3L), swapped(listOf(1, 2, 3), 1, -1))
        assertEquals(listOf(1L, 3L, 2L), swapped(listOf(1, 2, 3), 1, 1))
    }

    @Test
    fun `swapped is a no-op off either end`() {
        val ids = listOf(1L, 2L, 3L)
        assertSame(ids, swapped(ids, 0, -1))
        assertSame(ids, swapped(ids, 2, 1))
    }

    @Test
    fun `nextGroupColor picks the first unused swatch`() {
        val s = GroupEntity.SWATCHES
        assertEquals(s[0], nextGroupColor(emptyList()))
        assertEquals(s[1], nextGroupColor(listOf(group(1, color = s[0]))))
        assertEquals(s[0], nextGroupColor(listOf(group(1, color = s[1]))))
    }

    @Test
    fun `nextGroupColor wraps when every swatch is taken`() {
        val s = GroupEntity.SWATCHES
        val all = s.mapIndexed { i, c -> group(i.toLong(), color = c) }
        assertEquals(s[0], nextGroupColor(all))
        assertEquals(s[1], nextGroupColor(all + group(99, color = s[0])))
    }
}
