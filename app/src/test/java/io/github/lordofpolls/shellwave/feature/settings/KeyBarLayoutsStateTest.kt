package io.github.lordofpolls.shellwave.feature.settings

import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyBarLayoutsStateTest {
    private val blank = KeyBarLayoutEntity(name = "New layout", keysJson = "[]")

    @Test
    fun closingOrDeletingANewLayoutBeforeAnyEditLeavesNothing() {
        val opened = KeyBarLayoutsState().newLayout(blank)

        val closed = opened.toggle(0L)
        assertNull(closed.draft)
        assertNull(closed.expandedId)

        assertNull(opened.deleted(0L).draft)
    }

    @Test
    fun editingAnotherLayoutDiscardsTheUnsavedDraftButKeepsASavedOne() {
        val unsaved = KeyBarLayoutsState().newLayout(blank).toggle(5L)
        assertNull(unsaved.draft)
        assertEquals(5L, unsaved.expandedId)

        val saved = KeyBarLayoutsState(expandedId = 7L, draft = blank.copy(id = 7L)).toggle(5L)
        assertEquals(blank.copy(id = 7L), saved.draft)
    }

    @Test
    fun anEditDuringTheInsertIsWrittenBackUnderTheNewId() {
        val (inserting, first) = KeyBarLayoutsState().newLayout(blank).edited(blank.copy(name = "V"))
        val insert = first as LayoutWrite.Insert
        assertEquals("V", insert.layout.name)

        val (typedAgain, second) = inserting.edited(blank.copy(name = "Vi"))
        assertNull(second)
        // Closing mid-insert must not drop the draft the insert is about to name.
        val (done, followUp) = typedAgain.toggle(0L).inserted(42L, insert.layout)

        assertEquals(blank.copy(id = 42L, name = "Vi"), done.draft)
        assertEquals(LayoutWrite.Update(blank.copy(id = 42L, name = "Vi")), followUp)
        assertEquals(false, done.inserting)
    }

    @Test
    fun anInsertWithNothingTypedSinceNeedsNoFollowUp() {
        val (inserting, first) = KeyBarLayoutsState().newLayout(blank).edited(blank.copy(name = "V"))

        val (done, followUp) = inserting.inserted(42L, (first as LayoutWrite.Insert).layout)

        assertEquals(42L, done.expandedId)
        assertNull(followUp)
    }
}
