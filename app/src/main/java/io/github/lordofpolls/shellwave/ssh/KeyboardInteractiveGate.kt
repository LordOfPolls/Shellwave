package io.github.lordofpolls.shellwave.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.Resource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [sessionLabel] tells the user which server they are answering. Several sessions can authenticate
 * at once while only one prompt is shown at a time.
 */
data class KeyboardInteractivePrompt(
    val sessionLabel: String,
    val name: String,
    val instruction: String,
    val prompt: String,
    val echo: Boolean,
    val respond: (String) -> Unit,
)

/**
 * The same blocking bridge as [HostVerificationGate], for the keyboard-interactive 2FA dialog. Two
 * three-line classes beat the abstraction that would unify them.
 *
 * Keyed per provider for the same reason: [newProvider] hands out one per attempt, and two sessions
 * mid-prompt would otherwise clobber each other's single pending slot and leave an sshj auth thread
 * blocked forever.
 *
 * [cancel] has `HostVerificationGate.cancel`'s shape but not its reach. [requestResponse]'s
 * `runBlocking` sits in the same coroutine suspended inside [SshConnection.connect], so
 * [SessionManager.attemptConnect]'s `finally` cannot run while a prompt it raised is pending; by
 * the time it does, the prompt is resolved and its [cancel] is cleanup. The rescue is
 * SessionManager.closeSession calling [cancel] from outside the blocked coroutine, the only way to
 * release a stuck sshj auth thread when a session is closed mid-prompt.
 */
@Singleton
class KeyboardInteractiveGate @Inject constructor() {
    private val _pending = MutableStateFlow<Map<Any, KeyboardInteractivePrompt>>(emptyMap())
    val pending: StateFlow<Map<Any, KeyboardInteractivePrompt>> = _pending

    private val inFlight = ConcurrentHashMap<Any, CompletableDeferred<String>>()

    // No session exists yet when newProvider() is called, so the label is attached separately.
    private val labels = ConcurrentHashMap<Any, String>()

    /**
     * Called at the start of every attempt on that provider, reconnects included. resolve runs before
     * [SessionManager.openSession] assigns a session, so [newProvider] has nothing to attach yet.
     */
    fun label(token: Any, sessionLabel: String) {
        labels[token] = sessionLabel
    }

    private fun requestResponse(
        token: Any,
        name: String,
        instruction: String,
        prompt: String,
        echo: Boolean
    ): String =
        runBlocking {
            val deferred = CompletableDeferred<String>()
            inFlight[token] = deferred
            val sessionLabel = labels[token] ?: ""
            _pending.update {
                it + (token to KeyboardInteractivePrompt(
                    sessionLabel,
                    name,
                    instruction,
                    prompt,
                    echo
                ) { response ->
                    if (inFlight.remove(token) != null) {
                        _pending.update { m -> m - token }
                        deferred.complete(response)
                    }
                })
            }
            deferred.await()
        }

    /**
     * Resolves the possibly still-blocked [requestResponse] with an empty response. Empty rather than
     * throwing: sshj treats it as a failed answer and fails auth cleanly, which is the honest outcome
     * for a dead attempt. Safe to call unconditionally once an attempt ends.
     *
     * Two callers, covering different situations. [SessionManager.attemptConnect]'s `finally` is
     * ordinary cleanup; `SessionManager.closeSession` is the one that can free a thread still blocked
     * on a prompt that is on screen. The dialog's Cancel button resolves a live prompt a third way, by
     * calling [KeyboardInteractivePrompt.respond] directly.
     */
    fun cancel(token: Any) {
        labels.remove(token) // re-attached by label() before the next attempt on this provider, if any
        val deferred = inFlight.remove(token)
        if (deferred != null) {
            _pending.update { it - token }
            deferred.complete("")
        }
    }

    /** sshj calls [init] once, then [getResponse] per prompt the server sends. */
    fun newProvider(): ChallengeResponseProvider =
        object : ChallengeResponseProvider {
            private var name = ""
            private var instruction = ""

            override fun getSubmethods(): List<String> = emptyList()

            override fun init(resource: Resource<*>, name: String, instruction: String) {
                this.name = name
                this.instruction = instruction
            }

            override fun getResponse(prompt: String, echo: Boolean): CharArray =
                requestResponse(this, name, instruction, prompt, echo).toCharArray()

            override fun shouldRetry(): Boolean = false
        }
}
