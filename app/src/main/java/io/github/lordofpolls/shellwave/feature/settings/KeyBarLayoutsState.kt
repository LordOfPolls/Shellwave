package io.github.lordofpolls.shellwave.feature.settings

import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity

internal sealed interface LayoutWrite {
    data class Insert(val layout: KeyBarLayoutEntity) : LayoutWrite

    data class Update(val layout: KeyBarLayoutEntity) : LayoutWrite
}

/**
 * A new layout is only inserted on its first edit, so backing out of "New layout" leaves nothing
 * behind. Until then [draft] has id 0 and exists only while expanded. After the insert it stays the
 * rendered copy, so the card being typed into doesn't jump to its sorted place mid-edit.
 *
 * While [inserting], an id-0 draft is never dropped or replaced: [inserted] hands the new id to
 * whatever id-0 draft it finds, so it must still be the one that was inserted.
 */
internal data class KeyBarLayoutsState(
    val expandedId: Long? = null,
    val draft: KeyBarLayoutEntity? = null,
    val inserting: Boolean = false,
) {
    private val hasUnsavedDraft get() = draft?.id == 0L

    fun newLayout(blank: KeyBarLayoutEntity): KeyBarLayoutsState =
        copy(expandedId = 0L, draft = if (hasUnsavedDraft) draft else blank)

    fun toggle(id: Long): KeyBarLayoutsState {
        val next = if (expandedId == id) null else id
        val dropDraft = hasUnsavedDraft && next != 0L && !inserting
        return copy(expandedId = next, draft = if (dropDraft) null else draft)
    }

    fun deleted(id: Long): KeyBarLayoutsState =
        if (draft?.id != id || (id == 0L && inserting)) this else copy(draft = null)

    fun edited(updated: KeyBarLayoutEntity): Pair<KeyBarLayoutsState, LayoutWrite?> {
        val current = draft
        if (current == null || (updated.id != 0L && updated.id != current.id)) {
            return this to LayoutWrite.Update(updated)
        }
        val latest = updated.copy(id = current.id)
        return when {
            latest.id != 0L -> copy(draft = latest) to LayoutWrite.Update(latest)
            inserting -> copy(draft = latest) to null
            else -> copy(draft = latest, inserting = true) to LayoutWrite.Insert(latest)
        }
    }

    /** [written] is what the [LayoutWrite.Insert] carried; anything typed since is written back. */
    fun inserted(id: Long, written: KeyBarLayoutEntity): Pair<KeyBarLayoutsState, LayoutWrite?> {
        val pending = draft?.takeIf { it.id == 0L }?.copy(id = id)
        val next =
            copy(
                expandedId = if (expandedId == 0L) id else expandedId,
                draft = pending ?: draft,
                inserting = false,
            )
        return next to pending?.takeIf { it != written.copy(id = id) }?.let { LayoutWrite.Update(it) }
    }
}
