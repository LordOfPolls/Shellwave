package io.github.lordofpolls.shellwave.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val WAKE_ON_LAN_PORT = 9

/**
 * ponytail: fixed broadcast address; make it a per-host field if someone needs to wake across a
 * router that forwards directed broadcasts.
 */
private const val BROADCAST_ADDRESS = "255.255.255.255"

/** Six hex octets, optionally separated by `:`, `-`, or nothing. Null for anything else. */
fun parseMacAddress(mac: String): ByteArray? {
    val hex = mac.trim().filterNot { it == ':' || it == '-' || it == '.' }
    if (hex.length != 12 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Six `0xFF` bytes then the MAC repeated sixteen times. */
fun magicPacket(mac: String): ByteArray? {
    val address = parseMacAddress(mac) ?: return null
    val packet = ByteArray(6 + 16 * address.size)
    packet.fill(0xFF.toByte(), 0, 6)
    repeat(16) { address.copyInto(packet, 6 + it * address.size) }
    return packet
}

/**
 * Wake-on-LAN has no reply, so returning normally means the packet left the phone and nothing more.
 * Covered by the `ACCESS_LOCAL_NETWORK` permission MainActivity already requests.
 */
suspend fun sendMagicPacket(mac: String) {
    val payload = magicPacket(mac) ?: throw IllegalArgumentException("\"$mac\" is not a MAC address")
    withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(
                DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName(BROADCAST_ADDRESS),
                    WAKE_ON_LAN_PORT,
                ),
            )
        }
    }
}
