package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.password.PasswordUtils

/**
 * `sshj`'s own keepalive (`keepalive@openssh.com` global requests). 5 missed replies (sshj's own
 * default) x this interval is how long a silently-dead TCP connection takes to surface as an
 * [java.io.IOException] - see `SshConnection`'s class doc for where that matters for an interactive
 * shell. A headless [ScriptRunner] capture run is short-lived enough that this mostly never fires,
 * but it costs nothing to set the same way.
 */
internal const val KEEPALIVE_INTERVAL_SECONDS = 15

/**
 * Connect and authenticate one [SSHClient] - the half of "open an SSH connection" that has nothing
 * to do with what happens afterwards (a PTY + shell for SshConnection, a headless `exec` for
 * [ScriptRunner]'s capture mode). Both callers share every line of connect-and-auth logic,
 * including host key verification and every [AuthMethod] branch; only what runs after this differs.
 *
 * Callers run this on [kotlinx.coroutines.Dispatchers.IO] themselves (sshj's `connect`/`auth*`
 * calls block); this function does no dispatching of its own.
 *
 * [hostKeyVerifier] is mandatory, same as `SshConnection.connect`: there is no accept-all fallback
 * anywhere in this codebase.
 */
internal fun connectAndAuthenticate(
    ssh: SSHClient,
    host: String,
    port: Int,
    username: String,
    authMethod: AuthMethod,
    hostKeyVerifier: HostKeyVerifier,
) {
    ssh.addHostKeyVerifier(hostKeyVerifier)
    // Must be set before connect(); sshj starts the keepalive thread right after KEX, inside
    // connect() itself, only if the interval is already non-zero at that point.
    ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SECONDS
    ssh.connect(host, port)
    authenticate(ssh, username, authMethod)
}

/**
 * [connectAndAuthenticate]'s twin for a hop reached through an already-open ProxyJump tunnel.
 * [hostKeyVerifier] applies the same policy as a direct connection, never relaxed for a hop, and
 * the [AuthMethod] branches are shared via [authenticate] so the two paths cannot drift.
 */
internal fun connectViaAndAuthenticate(
    ssh: SSHClient,
    directConnection: DirectConnection,
    username: String,
    authMethod: AuthMethod,
    hostKeyVerifier: HostKeyVerifier,
) {
    ssh.addHostKeyVerifier(hostKeyVerifier)
    ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SECONDS
    ssh.connectVia(directConnection)
    authenticate(ssh, username, authMethod)
}

/** Shared by [connectAndAuthenticate] and [connectViaAndAuthenticate] so the two cannot drift. */
private fun authenticate(ssh: SSHClient, username: String, authMethod: AuthMethod) {
    when (authMethod) {
        is AuthMethod.Password -> ssh.authPassword(username, authMethod.password)
        is AuthMethod.PrivateKey -> {
            val passwordFinder =
                authMethod.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
            val keyProvider = ssh.loadKeys(authMethod.privateKeyPem, null, passwordFinder)
            ssh.authPublickey(username, keyProvider)
        }
        // TODO: this path is broken. sshj throws NullPointerException inside `UserAuthImpl.handle`
        // and reports it as "Exhausted available authentication methods". Reproduces identically on
        // an unoptimized debug build, so it is not R8. Not yet root-caused past that.
        is AuthMethod.KeyboardInteractive -> ssh.auth(
            username,
            AuthKeyboardInteractive(authMethod.provider)
        )
    }
}
