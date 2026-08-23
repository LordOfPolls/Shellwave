package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.userauth.method.ChallengeResponseProvider

sealed class AuthMethod {
    data class Password(val password: String) : AuthMethod()

    data class PrivateKey(val privateKeyPem: String, val passphrase: String?) : AuthMethod()

    data class KeyboardInteractive(val provider: ChallengeResponseProvider) : AuthMethod()
}
