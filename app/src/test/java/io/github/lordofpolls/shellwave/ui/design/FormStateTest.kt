package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormStateTest {

    private val scope = SaverScope { it is java.io.Serializable }

    private fun saved(slot: FormSlot) = with(FormSlotSaver) { scope.save(slot) }

    @Test
    fun `a restored edit waits for its row instead of being reset by the null first frame`() {
        val before = FormSlot(null)
        before.open<String>(null) { "" }
        before.open(5L) { "web1" }.value = "web1-edited"

        val after = FormSlotSaver.restore(saved(before)!!)!!
        assertEquals("", after.open(null) { "" }.value)
        assertEquals("web1-edited", after.open(5L) { "web1" }.value)
        assertEquals("web2", after.open(6L) { "web2" }.value)
    }

    @Test
    fun `an unsaveable value or key is not saved`() {
        val slot = FormSlot(null)
        slot.open(1L) { Any() }
        assertNull(saved(slot))
        val keyed = FormSlot(null)
        keyed.open(1L to Any()) { "x" }
        assertNull(saved(keyed))
    }
}
