package io.github.lordofpolls.shellwave.ssh

import io.github.lordofpolls.shellwave.core.net.parseMacAddress

/**
 * Asks the machine for the MAC of the interface this SSH connection arrived on.
 */
internal const val MAC_DETECTION_COMMAND =
    "set -- \$SSH_CONNECTION; " +
        "cat /sys/class/net/\$(ip -o addr show | awk -v a=\"\$3\" '\$4 ~ \"^\"a\"/\"{print \$2; exit}')/address"

/**
 * Loopback. Ignore
 */
fun macFromDetectionOutput(stdout: String): String? {
    val candidate = stdout.trim().lineSequence().firstOrNull()?.trim().orEmpty()
    val octets = parseMacAddress(candidate) ?: return null
    if (octets.all { it == 0.toByte() }) return null
    return octets.joinToString(":") { "%02x".format(it) }
}

suspend fun detectMacAddress(
    scriptRunner: ScriptRunner,
    runLabel: String,
    hostname: String,
    port: Int,
    username: String,
    authMethod: AuthMethod,
    hops: List<ProxyHop> = emptyList(),
): Result<String> {
    val result =
        scriptRunner.runCapture(
            runLabel,
            hostname,
            port,
            username,
            authMethod,
            MAC_DETECTION_COMMAND,
            hops,
        )
    result.error?.let { return Result.failure(IllegalStateException(it)) }
    val mac =
        macFromDetectionOutput(result.stdout)
            ?: return Result.failure(
                IllegalStateException(
                    buildString {
                        append("Couldn't read a MAC address from $username@$hostname.")
                        if (result.stderr.isNotBlank()) append(" ").append(result.stderr.trim().take(200))
                    },
                ),
            )
    return Result.success(mac)
}
