package app.anglerfish.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class Ipv4UdpPacketTest {

    @Test
    fun `build then parse round-trips source, dest, ports, and payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 54321,
            destAddress = InetAddress.getByName("8.8.8.8"),
            destPort = 53,
            payload = payload,
        )

        val datagram = Ipv4UdpPacket.parse(packet)

        requireNotNull(datagram)
        assertEquals(InetAddress.getByName("10.0.0.2"), datagram.sourceAddress)
        assertEquals(54321, datagram.sourcePort)
        assertEquals(InetAddress.getByName("8.8.8.8"), datagram.destAddress)
        assertEquals(53, datagram.destPort)
        assertArrayEquals(payload, datagram.payload)
    }

    @Test
    fun `build produces an IPv4 header whose checksum verifies to zero`() {
        val packet = Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 1,
            destAddress = InetAddress.getByName("10.0.0.3"),
            destPort = 2,
            payload = byteArrayOf(9),
        )

        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        assertEquals(0xFFFF, sum)
    }

    @Test
    fun `parse returns null for a non-IPv4 packet`() {
        val notIpv4 = ByteArray(28) { 0 }
        notIpv4[0] = 0x60 // version 6 in the high nibble

        assertNull(Ipv4UdpPacket.parse(notIpv4))
    }

    @Test
    fun `parse returns null for an IPv4 packet that is not UDP`() {
        val packet = Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 1,
            destAddress = InetAddress.getByName("10.0.0.3"),
            destPort = 2,
            payload = byteArrayOf(9),
        )
        packet[9] = 6 // overwrite protocol field: TCP instead of UDP

        assertNull(Ipv4UdpPacket.parse(packet))
    }

    @Test
    fun `parse returns null for a packet shorter than a minimal IPv4+UDP header`() {
        assertNull(Ipv4UdpPacket.parse(ByteArray(10)))
    }

    @Test
    fun `parse returns null when the UDP length field exceeds the packet's actual size`() {
        val packet = Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 1,
            destAddress = InetAddress.getByName("10.0.0.3"),
            destPort = 2,
            payload = byteArrayOf(9),
        )
        // UDP length field sits at byte offset 20+4=24..25 (big-endian); claim far more than exists.
        packet[24] = 0x7F
        packet[25] = 0xFF.toByte()

        assertNull(Ipv4UdpPacket.parse(packet))
    }

    @Test
    fun `parse returns null when the UDP length field is smaller than the UDP header itself`() {
        val packet = Ipv4UdpPacket.build(
            sourceAddress = InetAddress.getByName("10.0.0.2"),
            sourcePort = 1,
            destAddress = InetAddress.getByName("10.0.0.3"),
            destPort = 2,
            payload = byteArrayOf(9),
        )
        // UDP length field sits at byte offset 20+4=24..25; 4 is smaller than the 8-byte UDP header.
        packet[24] = 0
        packet[25] = 4

        assertNull(Ipv4UdpPacket.parse(packet))
    }

    @Test
    fun `parse succeeds for an IPv4 header with options (IHL greater than 5)`() {
        // Hand-built: version 4, IHL 6 (24-byte header = 20 + 4 bytes of options), UDP, with a
        // valid UDP header + 1-byte payload starting right after the header.
        val packet = ByteArray(24 + 8 + 1)
        packet[0] = 0x46 // version 4, IHL 6
        packet[9] = 17 // UDP
        InetAddress.getByName("10.0.0.2").address.copyInto(packet, 12)
        InetAddress.getByName("10.0.0.3").address.copyInto(packet, 16)
        // bytes 20-23 are the (unused by this parser) options bytes, left zeroed
        val udpStart = 24
        packet[udpStart] = 0 // source port high byte
        packet[udpStart + 1] = 1 // source port low byte
        packet[udpStart + 2] = 0 // dest port high byte
        packet[udpStart + 3] = 2 // dest port low byte
        packet[udpStart + 4] = 0 // UDP length high byte
        packet[udpStart + 5] = 9 // UDP length low byte (8-byte header + 1-byte payload)
        packet[udpStart + 8] = 42 // the 1-byte payload

        val datagram = Ipv4UdpPacket.parse(packet)

        requireNotNull(datagram)
        assertEquals(InetAddress.getByName("10.0.0.2"), datagram.sourceAddress)
        assertEquals(1, datagram.sourcePort)
        assertEquals(InetAddress.getByName("10.0.0.3"), datagram.destAddress)
        assertEquals(2, datagram.destPort)
        assertArrayEquals(byteArrayOf(42), datagram.payload)
    }

    @Test
    fun `parse returns null for a header with options that is truncated before the UDP header`() {
        // Same IHL-6 shape as above, but the packet ends before the full 24-byte header plus an
        // 8-byte UDP header can fit.
        val packet = ByteArray(24 + 8 - 1)
        packet[0] = 0x46 // version 4, IHL 6
        packet[9] = 17 // UDP
        InetAddress.getByName("10.0.0.2").address.copyInto(packet, 12)
        InetAddress.getByName("10.0.0.3").address.copyInto(packet, 16)

        assertNull(Ipv4UdpPacket.parse(packet))
    }
}
