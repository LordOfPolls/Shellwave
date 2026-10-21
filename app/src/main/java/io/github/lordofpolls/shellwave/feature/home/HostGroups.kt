package io.github.lordofpolls.shellwave.feature.home

import io.github.lordofpolls.shellwave.core.db.entities.GroupEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.prefs.HostSort

/** One block of the list: [group] null is the ungrouped block, which always renders last. */
data class HostSection(val group: GroupEntity?, val hosts: List<HostEntity>)

/**
 * Inputs arrive in DAO (alphabetical) order. Every sort here is stable, so that order is the
 * tiebreaker in every mode.
 */
fun buildSections(
    hosts: List<HostEntity>,
    groups: List<GroupEntity>,
    sort: HostSort,
): List<HostSection> {
    val known = groups.mapTo(HashSet()) { it.id }
    val byGroup = hosts.groupBy { host -> host.groupId?.takeIf { id -> id in known } }
    fun ordered(section: List<HostEntity>) =
        when (sort) {
            HostSort.ALPHABETICAL -> section
            HostSort.RECENT -> section.sortedByDescending { it.lastConnectedAt ?: Long.MIN_VALUE }
            HostSort.MOST_USED -> section.sortedByDescending { it.connectCount }
            HostSort.MANUAL -> section.sortedBy { it.position ?: Int.MAX_VALUE }
        }
    val headers =
        if (sort == HostSort.MANUAL) groups.sortedBy { it.position ?: Int.MAX_VALUE } else groups
    val ungrouped = byGroup[null].orEmpty()
    return buildList {
        headers.forEach { add(HostSection(it, ordered(byGroup[it.id].orEmpty()))) }
        if (ungrouped.isNotEmpty()) add(HostSection(null, ordered(ungrouped)))
    }
}

/** Renumbered from the displayed order, never swapped in place: null and duplicate slots can't corrupt it. */
fun positionsFor(ids: List<Long>): Map<Long, Int> = ids.withIndex().associate { (i, id) -> id to i }

fun swapped(ids: List<Long>, index: Int, delta: Int): List<Long> {
    val target = index + delta
    if (index !in ids.indices || target !in ids.indices) return ids
    return ids.toMutableList().apply {
        this[index] = ids[target]
        this[target] = ids[index]
    }
}

fun nextGroupColor(existing: List<GroupEntity>): Int {
    val used = existing.mapTo(HashSet()) { it.color }
    return GroupEntity.SWATCHES.firstOrNull { it !in used }
        ?: GroupEntity.SWATCHES[existing.size % GroupEntity.SWATCHES.size]
}
