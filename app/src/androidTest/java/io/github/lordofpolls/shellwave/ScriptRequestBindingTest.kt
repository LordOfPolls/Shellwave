package io.github.lordofpolls.shellwave

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.feature.scripts.ScriptRunController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduce stale dialog callbacks without executing commands or creating saved fixtures. */
@RunWith(AndroidJUnit4::class)
class ScriptRequestBindingTest {
    private fun script(id: Long, host: Long?) = ScriptEntity(
        id = id, name = "request-$id", icon = null, color = null, targetHostId = host,
        snippet = "true", mode = "CAPTURE", disconnectAfter = false, paramsJson = "",
        confirmBeforeRun = true, createdAt = 0,
    )
    private fun withController(check: (ScriptRunController) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            instrumentation.runOnMainSync {
                check(ScriptRunController(scope, activity.hostDao, activity.credentialVault, activity.scriptRunner,
                    activity.scriptRunDao, activity.sessionManager, null, {}))
            }
        } finally {
            scope.cancel()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun staleConfirmationCannotConsumeNewerTarget() = withController { controller ->
        val first = script(1, -1)
        val second = script(2, -2)
        controller.request(first)
        val firstRequestId = controller.pendingRequestId
        controller.request(second)
        controller.confirmRun(first, emptyMap(), firstRequestId)
        assertEquals(second, controller.pending)
        assertNull(controller.pendingHostChoice)
    }
    @Test fun staleHostPickerCannotReplaceNewerRequest() = withController { controller ->
        val first = script(1, null)
        val second = script(2, -2)
        controller.request(first)
        val firstRequestId = controller.pendingRequestId
        controller.request(second)
        assertNull(controller.pendingHostChoice)
        controller.chooseHost(first, -3, firstRequestId)
        assertEquals(second, controller.pending)
        assertNull(controller.pendingHostChoice)
    }
    @Test fun currentHostChoiceStillAdvancesToConfirmation() = withController { controller ->
        val current = script(1, null)
        controller.request(current)
        assertEquals(current, controller.pendingHostChoice)
        controller.chooseHost(current, -1, controller.pendingRequestId)
        assertNull(controller.pendingHostChoice)
        assertEquals(current, controller.pending)
    }
    @Test fun sameScriptOnAnotherHostRejectsOldConfirmation() = withController { controller ->
        val sameScript = script(1, null)
        controller.request(sameScript, hostId = -1)
        val oldRequestId = controller.pendingRequestId
        controller.request(sameScript, hostId = -2)
        controller.confirmRun(sameScript, emptyMap(), oldRequestId)
        assertEquals(sameScript, controller.pending)
        assertNull(controller.pendingHostChoice)
    }
    @Test fun repeatedSameScriptPickerRejectsOldChoice() = withController { controller ->
        val sameScript = script(1, null)
        controller.request(sameScript)
        val oldRequestId = controller.pendingRequestId
        controller.request(sameScript)
        controller.chooseHost(sameScript, -1, oldRequestId)
        assertEquals(sameScript, controller.pendingHostChoice)
        assertNull(controller.pending)
    }

}
