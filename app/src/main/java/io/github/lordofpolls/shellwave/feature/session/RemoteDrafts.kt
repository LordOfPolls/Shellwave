package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.ssh.RemoteText

/**
 * Unsaved editor buffers parked across Activity recreation (rotation, fold, theme switch). Process
 * memory, not a Bundle: a 2 MiB buffer would blow the Binder transaction limit.
 */
internal object RemoteDrafts {
    class Draft(val loaded: RemoteText, val text: String)

    private val drafts = HashMap<Pair<Long, String>, Draft>()

    fun retain(sessionId: Long, path: String, draft: Draft) {
        drafts[sessionId to path] = draft
    }

    fun take(sessionId: Long, path: String): Draft? = drafts.remove(sessionId to path)
}
