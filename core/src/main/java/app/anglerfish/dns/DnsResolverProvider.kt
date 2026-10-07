package app.anglerfish.dns

import java.net.InetAddress

interface DnsResolverProvider {
    fun currentResolvers(): List<InetAddress>
}
