package io.github.lordofpolls.shellwave.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.lordofpolls.shellwave.core.crypto.CredentialVault
import io.github.lordofpolls.shellwave.core.crypto.isBiometricCancellation
import io.github.lordofpolls.shellwave.core.db.dao.HostDao
import io.github.lordofpolls.shellwave.core.db.dao.ScriptDao
import io.github.lordofpolls.shellwave.ssh.resolveProxyChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class WidgetTrampolineActivity : FragmentActivity() {

    @Inject
    lateinit var scriptDao: ScriptDao

    @Inject
    lateinit var hostDao: HostDao

    @Inject
    lateinit var credentialVault: CredentialVault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scriptId = intent?.getLongExtra(EXTRA_SCRIPT_ID, -1L) ?: -1L
        if (scriptId < 0) {
            finish()
            return
        }
        lifecycleScope.launch {
            runTrampoline(scriptId)
            finish()
        }
    }

    private suspend fun runTrampoline(scriptId: Long) {
        try {
            val script = scriptDao.getById(scriptId) ?: error("script not found")
            backgroundTriggerRefusal(script, fromAutomation = false)?.let { error(it) }
            val hostId = script.targetHostId ?: error("no target host")
            val host = hostDao.getById(hostId) ?: error("host not found")

            val trigger = CredentialVault.TriggerAuth(scriptId, UUID.randomUUID().toString())
            credentialVault.resolveAndStash(trigger, host.credentialId, this)
            resolveProxyChain(host, hostDao).forEach { hop ->
                credentialVault.resolveAndStash(trigger, hop.credentialId, this)
            }
            ScriptTriggerService.startFromTrampoline(this, scriptId, trigger.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.isBiometricCancellation()) ScriptTriggerService.start(this, scriptId)
        }
    }

    companion object {
        fun intentFor(context: Context, scriptId: Long): Intent =
            Intent(context, WidgetTrampolineActivity::class.java).putExtra(EXTRA_SCRIPT_ID, scriptId)
    }
}
