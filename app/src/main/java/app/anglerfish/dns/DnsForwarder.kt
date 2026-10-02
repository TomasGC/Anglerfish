package app.anglerfish.dns

import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

interface DnsForwarder {
    suspend fun forward(resolvers: List<InetAddress>, query: ByteArray): ByteArray?
}

// protect() must run before the socket sends anything -- without it, this socket's own outbound
// packets re-enter the tunnel they're trying to escape, an infinite loop. A fresh socket per call
// (no persistent connection to leak or get wedged across a Wi-Fi/cellular handoff); resolvers are
// tried in order, first success wins, since a device can have more than one configured.
class UdpDnsForwarder(private val vpnService: VpnService) : DnsForwarder {

    override suspend fun forward(resolvers: List<InetAddress>, query: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            resolvers.firstNotNullOfOrNull { resolver -> forwardTo(resolver, query) }
        }

    private fun forwardTo(resolver: InetAddress, query: ByteArray): ByteArray? =
        try {
            DatagramSocket().use { socket ->
                vpnService.protect(socket)
                socket.soTimeout = FORWARD_TIMEOUT_MS
                // connect() restricts receive() to datagrams from this exact resolver -- without
                // it, an unconnected socket accepts a reply from any source on this ephemeral
                // port, leaving only the 16-bit DNS transaction ID to guard against a spoofed
                // answer.
                socket.connect(resolver, DNS_PORT)
                socket.send(DatagramPacket(query, query.size, resolver, DNS_PORT))
                val buffer = ByteArray(MAX_DNS_MESSAGE_SIZE)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)
                responsePacket.data.copyOfRange(0, responsePacket.length)
            }
        } catch (_: IOException) {
            null
        }

    private companion object {
        const val DNS_PORT = 53
        const val FORWARD_TIMEOUT_MS = 5000
        const val MAX_DNS_MESSAGE_SIZE = 4096
    }
}
