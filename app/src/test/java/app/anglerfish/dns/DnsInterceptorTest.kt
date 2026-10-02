package app.anglerfish.dns

import app.anglerfish.data.BlocklistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Type
import java.io.IOException
import java.net.InetAddress

class DnsInterceptorTest {

    private class FakeBlocklistRepository(
        private val blockedDomains: Set<String>,
        private val isBlockedThrows: Exception? = null,
    ) : BlocklistRepository {
        override val userAdditions: Flow<Set<String>> = MutableStateFlow(emptySet())
        override suspend fun addDomain(domain: String) = Unit
        override suspend fun removeDomain(domain: String) = Unit
        override suspend fun isBlocked(domain: String): Boolean {
            isBlockedThrows?.let { throw it }
            return domain in blockedDomains
        }
        override suspend fun refreshIfStale() = Unit
    }

    private class FakeDnsForwarder(private val response: ByteArray?) : DnsForwarder {
        var callCount = 0
            private set
        override suspend fun forward(resolvers: List<InetAddress>, query: ByteArray): ByteArray? {
            callCount++
            return response
        }
    }

    private class FakeDnsResolverProvider(
        private val resolvers: List<InetAddress> = emptyList(),
        private val currentResolversThrows: Exception? = null,
    ) : DnsResolverProvider {
        override fun currentResolvers(): List<InetAddress> {
            currentResolversThrows?.let { throw it }
            return resolvers
        }
    }

    private val resolverAddress = InetAddress.getByName("192.168.1.1")

    private fun queryPacketFor(domain: String, destPort: Int = 53): ByteArray {
        val name = Name.fromString(if (domain.endsWith(".")) domain else "$domain.")
        val question = Record.newRecord(name, Type.A, DClass.IN)
        val query = Message.newQuery(question)
        return Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 54321,
            destAddress = InetAddress.getByName("8.8.8.8"),
            destPort = destPort,
            payload = query.toWire(),
        )
    }

    @Test
    fun `a blocked domain gets an NXDOMAIN response addressed back to the original source`() = runTest {
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(setOf("doubleclick.net.")),
            resolverProvider = FakeDnsResolverProvider(listOf(resolverAddress)),
            forwarder = FakeDnsForwarder(response = null),
        )

        val result = interceptor.handle(queryPacketFor("doubleclick.net"))

        requireNotNull(result)
        val responseDatagram = Ipv4UdpPacket.parse(result)
        requireNotNull(responseDatagram)
        assertEquals(InetAddress.getByName("8.8.8.8"), responseDatagram.sourceAddress)
        assertEquals(53, responseDatagram.sourcePort)
        assertEquals(InetAddress.getByName("10.0.0.2"), responseDatagram.destAddress)
        assertEquals(54321, responseDatagram.destPort)
        val responseMessage = Message(responseDatagram.payload)
        assertEquals(Rcode.NXDOMAIN, responseMessage.getHeader().getRcode())
    }

    @Test
    fun `a non-blocked domain is forwarded and the real response relayed back with a swapped envelope`() = runTest {
        val realAnswer = Message.newQuery(Record.newRecord(Name.fromString("example.com."), Type.A, DClass.IN)).toWire()
        val forwarder = FakeDnsForwarder(response = realAnswer)
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(blockedDomains = emptySet()),
            resolverProvider = FakeDnsResolverProvider(listOf(resolverAddress)),
            forwarder = forwarder,
        )

        val result = interceptor.handle(queryPacketFor("example.com"))

        requireNotNull(result)
        val responseDatagram = Ipv4UdpPacket.parse(result)
        requireNotNull(responseDatagram)
        assertEquals(InetAddress.getByName("8.8.8.8"), responseDatagram.sourceAddress)
        assertEquals(53, responseDatagram.sourcePort)
        assertEquals(InetAddress.getByName("10.0.0.2"), responseDatagram.destAddress)
        assertEquals(54321, responseDatagram.destPort)
        assertArrayEquals(realAnswer, responseDatagram.payload)
        assertEquals(1, forwarder.callCount)
    }

    @Test
    fun `forward failure returns null instead of throwing`() = runTest {
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(blockedDomains = emptySet()),
            resolverProvider = FakeDnsResolverProvider(listOf(resolverAddress)),
            forwarder = FakeDnsForwarder(response = null),
        )

        val result = interceptor.handle(queryPacketFor("example.com"))

        assertNull(result)
    }

    @Test
    fun `an empty resolver list returns null without invoking the forwarder`() = runTest {
        val forwarder = FakeDnsForwarder(response = byteArrayOf(1))
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(blockedDomains = emptySet()),
            resolverProvider = FakeDnsResolverProvider(resolvers = emptyList()),
            forwarder = forwarder,
        )

        val result = interceptor.handle(queryPacketFor("example.com"))

        assertNull(result)
        assertEquals(0, forwarder.callCount)
    }

    @Test
    fun `a non-DNS UDP packet passes through as null without invoking the forwarder`() = runTest {
        val forwarder = FakeDnsForwarder(response = null)
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(blockedDomains = emptySet()),
            resolverProvider = FakeDnsResolverProvider(listOf(resolverAddress)),
            forwarder = forwarder,
        )
        val notDns = Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 54321,
            destAddress = InetAddress.getByName("10.0.0.3"),
            destPort = 8080,
            payload = byteArrayOf(1, 2, 3),
        )

        assertNull(interceptor.handle(notDns))
        assertEquals(0, forwarder.callCount)
    }

    @Test
    fun `a non-IPv4 packet passes through as null`() = runTest {
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(blockedDomains = emptySet()),
            resolverProvider = FakeDnsResolverProvider(listOf(resolverAddress)),
            forwarder = FakeDnsForwarder(response = null),
        )
        val notIpv4 = ByteArray(28) { 0 }
        notIpv4[0] = 0x60 // version 6 in the high nibble

        assertNull(interceptor.handle(notIpv4))
    }

    @Test
    fun `a TCP packet on port 53 passes through as null without invoking the forwarder`() = runTest {
        val forwarder = FakeDnsForwarder(response = null)
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(blockedDomains = emptySet()),
            resolverProvider = FakeDnsResolverProvider(listOf(resolverAddress)),
            forwarder = forwarder,
        )
        val tcpPacket = Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 54321,
            destAddress = InetAddress.getByName("8.8.8.8"),
            destPort = 53,
            payload = byteArrayOf(1, 2, 3),
        )
        tcpPacket[9] = 6 // overwrite protocol field: TCP instead of UDP

        assertNull(interceptor.handle(tcpPacket))
        assertEquals(0, forwarder.callCount)
    }

    @Test
    fun `a malformed DNS payload on port 53 passes through as null without invoking the forwarder`() = runTest {
        val forwarder = FakeDnsForwarder(response = null)
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(blockedDomains = emptySet()),
            resolverProvider = FakeDnsResolverProvider(listOf(resolverAddress)),
            forwarder = forwarder,
        )
        val garbage = Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 54321,
            destAddress = InetAddress.getByName("8.8.8.8"),
            destPort = 53,
            payload = byteArrayOf(1, 2, 3),
        )

        assertNull(interceptor.handle(garbage))
        assertEquals(0, forwarder.callCount)
    }

    @Test
    fun `a blocklist lookup failure returns null instead of throwing`() = runTest {
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(
                blockedDomains = emptySet(),
                isBlockedThrows = IOException("DataStore read failed"),
            ),
            resolverProvider = FakeDnsResolverProvider(listOf(resolverAddress)),
            forwarder = FakeDnsForwarder(response = null),
        )

        val result = interceptor.handle(queryPacketFor("example.com"))

        assertNull(result)
    }

    @Test
    fun `a resolver lookup failure returns null instead of throwing`() = runTest {
        val interceptor = DnsInterceptor(
            blocklistRepository = FakeBlocklistRepository(blockedDomains = emptySet()),
            resolverProvider = FakeDnsResolverProvider(
                currentResolversThrows = SecurityException("ACCESS_NETWORK_STATE missing"),
            ),
            forwarder = FakeDnsForwarder(response = null),
        )

        val result = interceptor.handle(queryPacketFor("example.com"))

        assertNull(result)
    }
}
