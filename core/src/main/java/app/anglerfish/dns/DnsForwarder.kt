package app.anglerfish.dns

import java.net.InetAddress

interface DnsForwarder {
    suspend fun forward(resolvers: List<InetAddress>, query: ByteArray): ByteArray?
}
