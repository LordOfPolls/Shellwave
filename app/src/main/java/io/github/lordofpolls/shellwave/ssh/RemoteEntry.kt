package io.github.lordofpolls.shellwave.ssh

/**
 * [isDirectory] is resolved rather than a raw file type - a symlink cannot be classified without
 * following it.
 *
 * [size] and [mtime] (seconds since epoch) default to -1, meaning "the server's attributes did not
 * include this field" - a real SFTP outcome, not just an unset default - so callers never mistake
 * it for a guessed zero.
 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = -1L,
    val mtime: Long = -1L,
)

/**
 * The picker asks for `.` or a typed `~/logs`, and only the server knows what those resolve to.
 * Showing the typed string instead would make the breadcrumb a guess, and "up" arithmetic on
 * `~/logs` would climb a tree that does not exist.
 */
data class RemoteListing(val path: String, val entries: List<RemoteEntry>)

/**
 * SFTP has no tilde expansion: `~` is an ordinary filename, and a server without one answers
 * `canonicalize("~/logs")` with "no such file" instead of the home directory the user meant.
 * Rewriting to `./logs` resolves it against the session's starting directory.
 *
 * `~user/...` is left alone. Nothing at the protocol level can resolve another user's home, so
 * rewriting could only guess.
 */
internal fun expandTildeForSftp(path: String): String =
    when {
        path.isBlank() -> "."
        path == "~" -> "."
        path.startsWith("~/") -> "." + path.removePrefix("~")
        else -> path
    }
