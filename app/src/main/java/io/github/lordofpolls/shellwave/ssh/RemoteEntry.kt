package io.github.lordofpolls.shellwave.ssh

/**
 * No size, mtime or permissions: a picker, not an SFTP browser, and every extra field is a column
 * someone would then want to sort by.
 *
 * [isDirectory] is resolved rather than a raw file type - a symlink cannot be classified without
 * following it.
 */
data class RemoteEntry(val name: String, val path: String, val isDirectory: Boolean)

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
