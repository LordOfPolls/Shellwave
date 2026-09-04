package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.userauth.method.ChallengeResponseProvider

sealed class AuthMethod {
    data class Password(val password: String) : AuthMethod()

    data class PrivateKey(
        val privateKeyPem: String,
        val passphrase: String?,
        /** The `-cert.pub` text alongside this key, if any - see `SshAuth.authenticate`. */
        val certificate: String? = null,
    ) : AuthMethod()

    data class KeyboardInteractive(val provider: ChallengeResponseProvider) : AuthMethod()
}
