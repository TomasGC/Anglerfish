package app.anglerfish.nat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class Ipv4TcpPacketTest {

    private fun segment(
        syn: Boolean = false,
        ack: Boolean = false,
        fin: Boolean = false,
        rst: Boolean = false,
        payload: ByteArray = ByteArray(0),
    ) = Ipv4TcpSegment(
        sourceAddress = InetAddress.getByName("10.0.0.2"),
        sourcePort = 54321,
        destAddress = InetAddress.getByName("93.184.216.34"),
        destPort = 443,
        sequenceNumber = 1000L,
        ackNumber = 2000L,
        syn = syn,
        ack = ack,
        fin = fin,
        rst = rst,
        psh = payload.isNotEmpty(),
        windowSize = 65535,
        payload = payload,
    )

    @Test
    fun `build then parse round-trips addresses, ports, sequence and ack numbers, and payload`() {
        val original = segment(ack = true, payload = byteArrayOf(1, 2, 3, 4, 5))

        val parsed = Ipv4TcpPacket.parse(Ipv4TcpPacket.build(original))

        requireNotNull(parsed)
        assertEquals(original.sourceAddress, parsed.sourceAddress)
        assertEquals(original.sourcePort, parsed.sourcePort)
        assertEquals(original.destAddress, parsed.destAddress)
        assertEquals(original.destPort, parsed.destPort)
        assertEquals(original.sequenceNumber, parsed.sequenceNumber)
        assertEquals(original.ackNumber, parsed.ackNumber)
        assertArrayEquals(original.payload, parsed.payload)
    }

    @Test
    fun `each flag round-trips independently`() {
        assertTrue(requireNotNull(Ipv4TcpPacket.parse(Ipv4TcpPacket.build(segment(syn = true)))).syn)
        assertTrue(requireNotNull(Ipv4TcpPacket.parse(Ipv4TcpPacket.build(segment(ack = true)))).ack)
        assertTrue(requireNotNull(Ipv4TcpPacket.parse(Ipv4TcpPacket.build(segment(fin = true)))).fin)
        assertTrue(requireNotNull(Ipv4TcpPacket.parse(Ipv4TcpPacket.build(segment(rst = true)))).rst)
        val plain = requireNotNull(Ipv4TcpPacket.parse(Ipv4TcpPacket.build(segment())))
        assertFalse(plain.syn)
        assertFalse(plain.ack)
        assertFalse(plain.fin)
        assertFalse(plain.rst)
    }

    @Test
    fun `build produces an IPv4 header whose checksum verifies to zero`() {
        val packet = Ipv4TcpPacket.build(segment(ack = true))

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
    fun `build produces a TCP segment whose checksum verifies to zero over the pseudo-header`() {
        val original = segment(ack = true, payload = byteArrayOf(9, 9, 9))
        val packet = Ipv4TcpPacket.build(original)
        val tcpPortion = packet.copyOfRange(20, packet.size)

        val pseudoHeader = ByteArray(12 + tcpPortion.size + (tcpPortion.size % 2))
        original.sourceAddress.address.copyInto(pseudoHeader, 0)
        original.destAddress.address.copyInto(pseudoHeader, 4)
        pseudoHeader[9] = 6
        pseudoHeader[10] = ((tcpPortion.size shr 8) and 0xFF).toByte()
        pseudoHeader[11] = (tcpPortion.size and 0xFF).toByte()
        tcpPortion.copyInto(pseudoHeader, 12)

        var sum = 0
        var i = 0
        while (i < pseudoHeader.size) {
            sum += ((pseudoHeader[i].toInt() and 0xFF) shl 8) or (pseudoHeader[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        assertEquals(0xFFFF, sum)
    }

    @Test
    fun `parse returns null for a non-IPv4 or non-TCP packet`() {
        val notIpv4 = ByteArray(40) { 0 }
        notIpv4[0] = 0x60
        assertNull(Ipv4TcpPacket.parse(notIpv4))

        val packet = Ipv4TcpPacket.build(segment(ack = true))
        packet[9] = 17 // overwrite protocol field: UDP instead of TCP
        assertNull(Ipv4TcpPacket.parse(packet))
    }

    @Test
    fun `parse returns null for a packet shorter than a minimal IPv4+TCP header`() {
        assertNull(Ipv4TcpPacket.parse(ByteArray(10)))
    }

    @Test
    fun `parse correctly skips TCP options when the data offset indicates a larger header`() {
        // Hand-built: 20-byte IPv4 header + a 24-byte TCP header (data offset 6 = 4 extra option
        // bytes) + a 1-byte payload right after the real header.
        val packet = ByteArray(20 + 24 + 1)
        packet[0] = 0x45
        packet[2] = 0 // total length high byte
        packet[3] = (20 + 24 + 1).toByte() // total length low byte
        packet[9] = 6
        InetAddress.getByName("10.0.0.2").address.copyInto(packet, 12)
        InetAddress.getByName("93.184.216.34").address.copyInto(packet, 16)
        val tcpStart = 20
        packet[tcpStart + 12] = (6 shl 4).toByte() // data offset = 6 words = 24 bytes
        packet[tcpStart + 13] = 0x10 // ACK flag
        packet[tcpStart + 24] = 42 // payload, right after the 24-byte TCP header

        val parsed = Ipv4TcpPacket.parse(packet)

        requireNotNull(parsed)
        assertArrayEquals(byteArrayOf(42), parsed.payload)
    }

    @Test
    fun `parse returns null when the data offset claims less than the minimum TCP header size`() {
        val packet = Ipv4TcpPacket.build(segment(ack = true))
        packet[32] = (4 shl 4).toByte() // data offset = 4 words = 16 bytes, below the 20-byte minimum

        assertNull(Ipv4TcpPacket.parse(packet))
    }

    @Test
    fun `parse returns null when the IHL claims a header shorter than the 20-byte minimum`() {
        // Hand-built so the TCP header is self-consistent at the (too-short) IHL's own offset, and
        // padded to the overall 40-byte floor -- otherwise a coincidental misread or the unrelated
        // overall-size guard could reject it for the wrong reason.
        val packet = ByteArray(16 + 20 + 4)
        packet[0] = 0x44 // version 4, IHL 4 words = 16 bytes, below the 20-byte minimum
        packet[9] = 6 // protocol: TCP
        val tcpStart = 16
        packet[tcpStart + 12] = (5 shl 4).toByte() // data offset = 5 words = 20 bytes, no options
        packet[tcpStart + 13] = 0x10 // ACK flag

        assertNull(Ipv4TcpPacket.parse(packet))
    }

    @Test
    fun `parse returns null for a packet with the more-fragments flag set`() {
        val packet = Ipv4TcpPacket.build(segment(ack = true))
        packet[6] = 0x20 // flags byte: MF bit set, fragment offset 0

        assertNull(Ipv4TcpPacket.parse(packet))
    }

    @Test
    fun `parse returns null for a non-initial fragment (nonzero fragment offset)`() {
        val packet = Ipv4TcpPacket.build(segment(ack = true))
        packet[6] = 0x00
        packet[7] = 0x01 // fragment offset = 1 (in 8-byte units), not the first fragment

        assertNull(Ipv4TcpPacket.parse(packet))
    }

    @Test
    fun `parse ignores trailing bytes past the IPv4 total-length field`() {
        val original = segment(ack = true, payload = byteArrayOf(1, 2, 3))
        val packet = Ipv4TcpPacket.build(original) + byteArrayOf(9, 9, 9, 9, 9)

        val parsed = Ipv4TcpPacket.parse(packet)

        requireNotNull(parsed)
        assertArrayEquals(original.payload, parsed.payload)
    }

    @Test
    fun `parse returns null when the total-length field claims more bytes than are present`() {
        val packet = Ipv4TcpPacket.build(segment(ack = true, payload = byteArrayOf(1, 2, 3)))
        packet[2] = 0x7F // total length high byte: claim an implausibly large total length
        packet[3] = 0xFF.toByte()

        assertNull(Ipv4TcpPacket.parse(packet))
    }
}
