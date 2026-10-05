package app.anglerfish.dns

import app.anglerfish.data.BlocklistRepository
import kotlinx.coroutines.CancellationException
import org.xbill.DNS.Message

class DnsInterceptor(
    private val blocklistRepository: BlocklistRepository,
    private val resolverProvider: DnsResolverProvider,
    private val forwarder: DnsForwarder,
) {
    suspend fun handle(rawPacket: ByteArray): ByteArray? {
        val datagram = Ipv4UdpPacket.parse(rawPacket)?.takeIf { it.destPort == DNS_PORT } ?: return null
        val query = DnsMessages.parseQuery(datagram.payload) ?: return null
        val responsePayload = resolveResponse(query, datagram.payload) ?: return null

        return Ipv4UdpPacket.build(
            sourceAddress = datagram.destAddress,
            sourcePort = datagram.destPort,
            destAddress = datagram.sourceAddress,
            destPort = datagram.sourcePort,
            payload = responsePayload,
        )
    }

    // isBlocked() reads DataStore (can throw IOException/CorruptionException) and
    // currentResolvers() reads ConnectivityManager (can throw SecurityException, e.g. a missing
    // permission) -- neither is a "malformed input" case the parser layers already guard, so this
    // boundary is this function's own responsibility. CancellationException is rethrown so a
    // caller's coroutine cancellation (e.g. the tunnel shutting down mid-query) isn't swallowed.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveResponse(query: Message, rawQuery: ByteArray): ByteArray? =
        try {
            if (blocklistRepository.isBlocked(DnsMessages.domainOf(query))) {
                DnsMessages.buildNxDomainResponse(query)
            } else {
                val resolvers = resolverProvider.currentResolvers()
                if (resolvers.isEmpty()) null else forwarder.forward(resolvers, rawQuery)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: Exception) {
            null
        }

    private companion object {
        const val DNS_PORT = 53
    }
}
