package app.anglerfish.nat

import app.anglerfish.dns.Ipv4UdpPacket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NatRelayTest {

    private val factory = FakeSessionFactory()
    private val relay = NatRelay(factory)

    @Test
    fun `UDP session is closed once idle for 60s and not before`() {
        relay.handleOutgoingPacket(udpPacket(), now = 0L)

        relay.evictIdle(now = 59_999L)
        assertFalse(factory.udp.single().closed)

        relay.evictIdle(now = 60_000L)
        assertTrue(factory.udp.single().closed)
    }

    @Test
    fun `TCP session outlives the UDP timeout and is closed once idle for 5 minutes`() {
        relay.handleOutgoingPacket(tcpSyn(), now = 0L)

        relay.evictIdle(now = 60_000L)
        assertFalse(factory.tcp.single().closed)

        relay.evictIdle(now = 5 * 60_000L - 1)
        assertFalse(factory.tcp.single().closed)

        relay.evictIdle(now = 5 * 60_000L)
        assertTrue(factory.tcp.single().closed)
    }

    private fun udpPacket(): ByteArray = Ipv4UdpPacket.build(
        sourceAddress = InetAddress.getByName("10.0.0.2"),
        sourcePort = 5353,
        destAddress = InetAddress.getByName("1.1.1.1"),
        destPort = 53,
        payload = byteArrayOf(1),
    )

    private fun tcpSyn(): ByteArray = Ipv4TcpPacket.build(
        Ipv4TcpSegment(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 54321,
            destAddress = InetAddress.getByName("93.184.216.34"),
            destPort = 443,
            sequenceNumber = 1000L,
            ackNumber = 0L,
            syn = true,
            ack = false,
            fin = false,
            rst = false,
            psh = false,
            windowSize = 65535,
            payload = ByteArray(0),
        ),
    )

    private class FakeUdpSession : UdpSession {
        var closed = false
        override fun sendToDestination(payload: ByteArray) = Unit
        override fun close() {
            closed = true
        }
    }

    private class FakeTcpSession : TcpSession {
        var closed = false
        override fun isActive(): Boolean = !closed
        override fun start(appSequenceNumber: Long, appWindow: Int) = Unit
        override fun handle(segment: Ipv4TcpSegment) = Unit
        override fun close() {
            closed = true
        }
    }

    private class FakeSessionFactory : SessionFactory {
        val udp = mutableListOf<FakeUdpSession>()
        val tcp = mutableListOf<FakeTcpSession>()

        override fun createUdp(endpoints: FlowEndpoints, onClosed: () -> Unit): UdpSession =
            FakeUdpSession().also { udp += it }

        override fun createTcp(endpoints: FlowEndpoints, onClosed: () -> Unit): TcpSession =
            FakeTcpSession().also { tcp += it }
    }
}
