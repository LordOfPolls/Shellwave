package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val SSHJ_EXHAUSTED_MESSAGE = "Exhausted available authentication methods"

/**
 * sshj reports a plain wrong password as "Exhausted available authentication methods", which
 * describes its own loop over candidate methods and so reads like a missing algorithm or provider.
 * That sent one debugging session after an imaginary R8 regression while the server's log had the
 * same event as `Failed password`.
 *
 * Matching a library's literal string is fragile across upgrades, but it is tolerable here because
 * of how it breaks: a reworded sshj release stops matching and the original message reaches the
 * user, which is where this started. The replacement claims only what sshj actually reports, that
 * the server rejected the attempt, and offers the username as a suspect because a wrong one fails
 * identically.
 *
 * Everything else passes through. sshj's other failures already name their cause, and friendlier
 * phrasing would cost the diagnosis.
 */
internal fun describeConnectFailure(e: Throwable, authMethod: AuthMethod): String =
    when {
        e is UserAuthException && e.message == SSHJ_EXHAUSTED_MESSAGE ->
            when (authMethod) {
                is AuthMethod.Password -> "Authentication failed - the server rejected that username or password."
                is AuthMethod.PrivateKey -> "Authentication failed - the server rejected that username or key."
                // The username was already accepted, or the method would not have prompted.
                is AuthMethod.KeyboardInteractive -> "Authentication failed - the server rejected that response."
            }
        // The stock JDK messages are too bare to stand alone: "Connection refused" with no subject,
        // a bare hostname, and "Timeout expired: 30000 MILLISECONDS".
        e is ConnectException -> "Could not reach the server - nothing accepted a connection on that host and port."
        e is UnknownHostException -> "Could not find that host - the name did not resolve."
        e is SocketTimeoutException -> "The server did not respond in time."
        else -> e.message ?: "Connection failed"
    }
