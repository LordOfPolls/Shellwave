package io.github.lordofpolls.shellwave

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lordofpolls.shellwave.core.db.DatabaseModule
import io.github.lordofpolls.shellwave.core.db.entities.CredentialEntity
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Uses shell UID intent delivery to exercise the exported boundary on cold and warm launches. */
@RunWith(AndroidJUnit4::class)
class ShortcutConfirmationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) findText(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun awaitText(text: String): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            findText(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
            Thread.sleep(100)
        }
        return null
    }
    private fun cancel() {
        val button = awaitText("Cancel")
        assertNotNull("Consent dialog should have a Cancel button", button)
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    @Test fun externalShortcutRequiresConsentOnColdAndWarmLaunch() = runBlocking {
        val db = DatabaseModule.provideDatabase(context)
        val credential = CredentialEntity(type = "PASSWORD", label = null, keystoreAlias = null,
            secretIv = null, secretCiphertext = null, passphraseIv = null, passphraseCiphertext = null,
            publicKeyText = null, createdAt = 0)
        val credentialId = db.credentialDao().insert(credential)
        val host = HostEntity(label = "audit fixture", hostname = "127.0.0.1", port = 1,
            username = "audit", credentialId = credentialId, lastConnectedAt = null, createdAt = 0)
        val hostId = db.hostDao().insert(host)
        val script = ScriptEntity(name = "Audit consent fixture", icon = null, color = null,
            targetHostId = hostId, snippet = "printf audit-test", mode = "CAPTURE",
            disconnectAfter = false, paramsJson = "", confirmBeforeRun = false, createdAt = 0,
            allowAutomation = false)
        val scriptId = db.scriptDao().insert(script)
        try {
            // MainActivity does not receive an automation token or any app-internal marker.
            val component = "${context.packageName}/.MainActivity"
            repeat(2) {
                shell("am start -W -n $component --el io.github.lordofpolls.shellwave.extra.RUN_SCRIPT_ID $scriptId")
                assertNotNull(awaitText("Run \"${script.name}\"?"))
                cancel()
            }
            shell("am start -W -n $component --el io.github.lordofpolls.shellwave.extra.OPEN_HOST_ID $hostId")
            assertNotNull(awaitText("Connect to saved host?"))
            cancel()
        } finally {
            db.scriptDao().delete(script.copy(id = scriptId))
            db.hostDao().delete(host.copy(id = hostId))
            db.credentialDao().delete(credential.copy(id = credentialId))
            db.close()
        }
    }
}
