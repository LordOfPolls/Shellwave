package io.github.lordofpolls.shellwave.ssh

import java.io.IOException
import java.net.ServerSocket
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectTimeoutTest {

    @Test
    fun connectAndAuthenticate_boundsTheSocketConnect() {
        // A port that was just free and is now closed refuses at once, so nothing waits.
        val port = ServerSocket(0).use { it.localPort }
        val ssh = SSHClient()
        try {
            connectAndAuthenticate(
                ssh,
                "127.0.0.1",
                port,
                "user",
                AuthMethod.Password("pw"),
                PromiscuousVerifier(),
            )
        } catch (_: IOException) {
        }
        assertEquals(CONNECT_TIMEOUT_MS, ssh.connectTimeout)
    }
}
