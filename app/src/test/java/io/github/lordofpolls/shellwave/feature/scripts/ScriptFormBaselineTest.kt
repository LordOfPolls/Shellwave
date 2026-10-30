package io.github.lordofpolls.shellwave.feature.scripts

import androidx.compose.runtime.saveable.SaverScope
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.ui.design.FormSlot
import io.github.lordofpolls.shellwave.ui.design.FormSlotSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScriptFormBaselineTest {

    private val row = ScriptEntity(
        id = 5,
        name = "deploy",
        icon = null,
        color = ScriptColor.DEFAULT,
        targetHostId = 1,
        snippet = "systemctl restart {{unit}}",
        mode = ScriptMode.ATTACH.name,
        disconnectAfter = false,
        paramsJson = encodeParams(listOf(ScriptParam("unit"))),
        confirmBeforeRun = false,
        createdAt = 1,
    )

    private fun form(baseline: ScriptEntity, name: String) = scriptFormEntity(
        row, name, baseline.color!!, baseline.targetHostId, baseline.snippet, ScriptMode.valueOf(baseline.mode),
        baseline.disconnectAfter, baseline.paramsJson, baseline.confirmBeforeRun, baseline.allowAutomation,
    )

    @Test
    fun `a name restored after recreation still differs from the baseline`() {
        val key = row.id to null
        val before = FormSlot(null)
        before.open(key) { row.name }.value = "deploy-edited"
        val saved = with(FormSlotSaver) { SaverScope { it is java.io.Serializable }.save(before) }!!

        val after = FormSlotSaver.restore(saved)!!
        val baseline = scriptFormBaseline(row, row)
        val restoredName = after.open(key) { baseline.name }.value

        assertEquals(baseline, form(baseline, baseline.name))
        assertNotEquals(baseline, form(baseline, restoredName))
    }
}
