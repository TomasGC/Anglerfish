package app.anglerfish.dns

import app.anglerfish.data.BlocklistRepository
import app.anglerfish.data.isDomainBlocked
import app.anglerfish.data.parseHostsFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Type
import java.net.InetAddress

// DnsInterceptor wired to the real DNS wire-format code, the real hosts-file parser and the real
// suffix matcher. Only the two boundaries that reach the network or Android are substituted: the
// forwarder and the resolver list.
class DnsInterceptorPipelineTest {

    private val resolver = InetAddress.getByName("8.8.8.8")

    @Test
    fun `a subdomain of a hosts-file entry is answered NXDOMAIN without forwarding`() = runTest {
        val forwarder = RecordingForwarder(response = null)
        val interceptor = interceptorFor(hosts = "0.0.0.0 doubleclick.net", forwarder = forwarder)

        val result = interceptor.handle(queryPacketFor("ads.doubleclick.net"))

        val response = Message(payloadOf(requireNotNull(result)))
        assertEquals(Rcode.NXDOMAIN, response.getHeader().getRcode())
        assertTrue(forwarder.calls.isEmpty())
    }

    @Test
    fun `a domain absent from the hosts file is forwarded and its answer relayed`() = runTest {
        val answer = Message.newQuery(Record.newRecord(Name.fromString("example.com."), Type.A, DClass.IN)).toWire()
        val forwarder = RecordingForwarder(response = answer)
        val interceptor = interceptorFor(hosts = "0.0.0.0 doubleclick.net", forwarder = forwarder)
        val query = queryPacketFor("example.com")

        val result = interceptor.handle(query)

        assertArrayEquals(answer, payloadOf(requireNotNull(result)))
        val call = forwarder.calls.single()
        assertEquals(listOf(resolver), call.resolvers)
        assertArrayEquals(payloadOf(query), call.query)
    }

    @Test
    fun `hosts-file bootstrap lines do not block the localhost name`() = runTest {
        val answer = Message.newQuery(Record.newRecord(Name.fromString("localhost."), Type.A, DClass.IN)).toWire()
        val forwarder = RecordingForwarder(response = answer)
        val hosts = "127.0.0.1 localhost\n0.0.0.0 0.0.0.0\n0.0.0.0 tracker.example"
        val interceptor = interceptorFor(hosts = hosts, forwarder = forwarder)

        val result = interceptor.handle(queryPacketFor("localhost"))

        assertArrayEquals(answer, payloadOf(requireNotNull(result)))
        assertEquals(1, forwarder.calls.size)
    }

    private fun interceptorFor(hosts: String, forwarder: DnsForwarder) = DnsInterceptor(
        blocklistRepository = HostsFileBlocklist(parseHostsFile(hosts)),
        resolverProvider = FixedResolverProvider(listOf(resolver)),
        forwarder = forwarder,
    )

    private fun queryPacketFor(domain: String): ByteArray {
        val question = Record.newRecord(Name.fromString("$domain."), Type.A, DClass.IN)
        return Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 54321,
            destAddress = InetAddress.getByName("8.8.8.8"),
            destPort = DNS_PORT,
            payload = Message.newQuery(question).toWire(),
        )
    }

    private fun payloadOf(packet: ByteArray): ByteArray = requireNotNull(Ipv4UdpPacket.parse(packet)).payload

    private class HostsFileBlocklist(private val entries: Set<String>) : BlocklistRepository {
        override val userAdditions: Flow<Set<String>> = flowOf(emptySet())
        override suspend fun addDomain(domain: String) = error("not used by the interceptor")
        override suspend fun removeDomain(domain: String) = error("not used by the interceptor")
        override suspend fun isBlocked(domain: String): Boolean = isDomainBlocked(domain, entries)
        override suspend fun refreshIfStale() = error("not used by the interceptor")
    }

    private class FixedResolverProvider(private val resolvers: List<InetAddress>) : DnsResolverProvider {
        override fun currentResolvers(): List<InetAddress> = resolvers
    }

    private class RecordingForwarder(private val response: ByteArray?) : DnsForwarder {
        val calls = mutableListOf<Call>()

        override suspend fun forward(resolvers: List<InetAddress>, query: ByteArray): ByteArray? {
            calls += Call(resolvers, query)
            return response
        }

        class Call(val resolvers: List<InetAddress>, val query: ByteArray)
    }

    private companion object {
        const val DNS_PORT = 53
    }
}
