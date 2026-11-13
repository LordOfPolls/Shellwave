package io.github.lordofpolls.shellwave.ssh

import io.github.lordofpolls.shellwave.core.util.posixQuote
import javax.inject.Inject
import javax.inject.Singleton

internal const val ENROLMENT_OK_MARKER = "SHELLWAVE_ENROLL_OK"
internal const val ENROLMENT_FAIL_MARKER = "SHELLWAVE_ENROLL_FAIL"

data class KeyEnrolmentResult(val success: Boolean, val message: String)

/**
 * `authorized_keys` may not end in a newline, and appending to such a file would glue the new key
 * onto the last entry and corrupt both. `tail -c1 | grep -q .` only succeeds when the last byte is
 * not a newline (an empty file has no byte at all), so a newline is only added when the file ends
 * mid-line.
 *
 * sshd runs the command through the account's login shell, which can be csh or tcsh, so it sticks
 * to syntax both csh and POSIX sh accept: `&&`, `||`, `|`, `;` and `( )` subshells, but no command
 * substitution, braces or `[ ]` tests.
 */
internal fun enrolmentCommand(key: String): String {
    val quotedKey = posixQuote(key)
    val file = "~/.ssh/authorized_keys"
    return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch $file && chmod 600 $file && " +
            "(grep -qxF -- $quotedKey $file || " +
            "(tail -c1 $file | grep -q . && echo >> $file; printf '%s\\n' $quotedKey >> $file)) && " +
            "grep -qxF -- $quotedKey $file && echo $ENROLMENT_OK_MARKER || echo $ENROLMENT_FAIL_MARKER"
}

/**
 * `ssh-copy-id` over an already-authenticated session: one POSIX shell command through
 * [ScriptRunner.runCapture] rather than a second exec path. Nothing sshj-specific lives here.
 *
 * The API takes a public key line and has no parameter through which private key material could
 * arrive. The command greps for the [posixQuote]d line before appending, so it is idempotent, and
 * it only ever appends.
 *
 * Success is decided by a marker in stdout over the exit status, and that is no shortcut.
 * `A && B || C` exits with whichever of `B`/`C` ran last, and both are `echo`, so the status is 0
 * whether the read-back found the key or not. A failed `mkdir`/`chmod`/`touch` never reaches either `echo`,
 * leaving stdout with neither marker, which also counts as failure.
 */
@Singleton
class KeyEnrolment
@Inject
constructor(private val scriptRunner: ScriptRunner) {
    /**
     * Enrolment rides on [authMethod], the host's existing authentication, instead of establishing new
     * trust. [hops] is needed because a host behind a bastion has to be dialled the way a normal
     * connect would dial it.
     */
    suspend fun enroll(
        runLabel: String,
        hostname: String,
        port: Int,
        username: String,
        authMethod: AuthMethod,
        publicKeyLine: String,
        hops: List<ProxyHop> = emptyList(),
    ): KeyEnrolmentResult {
        val key = publicKeyLine.trim()
        require(key.isNotEmpty() && !key.contains('\n')) { "publicKeyLine must be a single non-blank line" }
        val command = enrolmentCommand(key)

        val result =
            scriptRunner.runCapture(runLabel, hostname, port, username, authMethod, command, hops)
        return when {
            result.error != null -> KeyEnrolmentResult(false, result.error)
            ENROLMENT_OK_MARKER in result.stdout && ENROLMENT_FAIL_MARKER !in result.stdout ->
                KeyEnrolmentResult(true, "Key installed on $username@$hostname.")

            else ->
                KeyEnrolmentResult(
                    false,
                    buildString {
                        append("Could not confirm the key was installed (exit ${result.exitStatus ?: "unknown"}).")
                        if (result.stderr.isNotBlank()) append(" ").append(result.stderr.take(500))
                    },
                )
        }
    }
}
