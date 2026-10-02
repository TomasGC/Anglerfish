package app.anglerfish.nat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.net.InetAddress

class FlowKeyTest {

    @Test
    fun `two FlowKeys with identical fields are equal and hash the same`() {
        val a = FlowKey(TransportProtocol.TCP, InetAddress.getByName("10.0.0.2"), 1, InetAddress.getByName("8.8.8.8"), 2)
        val b = FlowKey(TransportProtocol.TCP, InetAddress.getByName("10.0.0.2"), 1, InetAddress.getByName("8.8.8.8"), 2)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `FlowKeys differing only by protocol are not equal`() {
        val tcp = FlowKey(TransportProtocol.TCP, InetAddress.getByName("10.0.0.2"), 1, InetAddress.getByName("8.8.8.8"), 2)
        val udp = FlowKey(TransportProtocol.UDP, InetAddress.getByName("10.0.0.2"), 1, InetAddress.getByName("8.8.8.8"), 2)

        assertNotEquals(tcp, udp)
    }

    @Test
    fun `toEndpoints carries the address and port fields without the protocol`() {
        val key = FlowKey(TransportProtocol.UDP, InetAddress.getByName("10.0.0.2"), 1, InetAddress.getByName("8.8.8.8"), 2)

        val endpoints = key.toEndpoints()

        assertEquals(InetAddress.getByName("10.0.0.2"), endpoints.sourceAddress)
        assertEquals(1, endpoints.sourcePort)
        assertEquals(InetAddress.getByName("8.8.8.8"), endpoints.destAddress)
        assertEquals(2, endpoints.destPort)
    }
}
