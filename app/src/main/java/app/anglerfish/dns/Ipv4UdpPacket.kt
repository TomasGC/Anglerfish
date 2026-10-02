package app.anglerfish.dns

import java.net.InetAddress

data class Ipv4UdpDatagram(
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destAddress: InetAddress,
    val destPort: Int,
    val payload: ByteArray,
)

private const val IPV4_VERSION_IHL_NO_OPTIONS = 0x45
private const val IPV4_HEADER_LENGTH = 20
private const val UDP_HEADER_LENGTH = 8
private const val PROTOCOL_UDP = 17
private const val DEFAULT_TTL = 64
private const val IPV4_ADDRESS_LENGTH = 4
private const val IPV4_VERSION = 4
private const val BYTE_MASK = 0xFF
private const val UINT16_MASK = 0xFFFF
private const val BITS_PER_BYTE = 8
private const val BITS_PER_UINT16 = 16
private const val VERSION_SHIFT = 4
private const val IHL_MASK = 0x0F
private const val BYTES_PER_IHL_UNIT = 4
private const val VERSION_IHL_OFFSET = 0
private const val TOTAL_LENGTH_OFFSET = 2
private const val TTL_OFFSET = 8
private const val PROTOCOL_OFFSET = 9
private const val CHECKSUM_OFFSET = 10
private const val SOURCE_ADDR_OFFSET = 12
private const val DEST_ADDR_OFFSET = 16
private const val PORT_FIELD_SIZE = 2
private const val UDP_LENGTH_FIELD_OFFSET = 4

// Hand-rolled, not a dependency: the IPv4/UDP envelope has fixed-size headers and no
// variable-length/compressed encoding, unlike the DNS message format it carries (see
// DnsMessages.kt, which does use a library for exactly that reason).
object Ipv4UdpPacket {

    fun parse(raw: ByteArray): Ipv4UdpDatagram? {
        if (raw.size < IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH) return null

        val versionIhl = raw[VERSION_IHL_OFFSET].toInt() and BYTE_MASK
        val version = versionIhl shr VERSION_SHIFT
        val headerLength = (versionIhl and IHL_MASK) * BYTES_PER_IHL_UNIT
        val protocol = raw[PROTOCOL_OFFSET].toInt() and BYTE_MASK
        val envelopeValid = version == IPV4_VERSION &&
            protocol == PROTOCOL_UDP &&
            raw.size >= headerLength + UDP_HEADER_LENGTH
        if (!envelopeValid) return null

        val udpStart = headerLength
        val sourcePort = readUInt16(raw, udpStart)
        val destPort = readUInt16(raw, udpStart + PORT_FIELD_SIZE)
        val udpLength = readUInt16(raw, udpStart + UDP_LENGTH_FIELD_OFFSET)
        val payloadStart = udpStart + UDP_HEADER_LENGTH
        val payloadEnd = udpStart + udpLength
        if (payloadEnd > raw.size || payloadEnd < payloadStart) return null

        return Ipv4UdpDatagram(
            sourceAddress = InetAddress.getByAddress(
                raw.copyOfRange(SOURCE_ADDR_OFFSET, SOURCE_ADDR_OFFSET + IPV4_ADDRESS_LENGTH),
            ),
            sourcePort = sourcePort,
            destAddress = InetAddress.getByAddress(
                raw.copyOfRange(DEST_ADDR_OFFSET, DEST_ADDR_OFFSET + IPV4_ADDRESS_LENGTH),
            ),
            destPort = destPort,
            payload = raw.copyOfRange(payloadStart, payloadEnd),
        )
    }

    // UDP checksum is deliberately left at 0 ("not computed") -- explicitly valid for IPv4 per
    // RFC 768, and skips needing a pseudo-header checksum for a tunnel-internal packet.
    fun build(
        sourceAddress: InetAddress,
        sourcePort: Int,
        destAddress: InetAddress,
        destPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLength = IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH + payload.size
        val packet = ByteArray(totalLength)

        packet[VERSION_IHL_OFFSET] = IPV4_VERSION_IHL_NO_OPTIONS.toByte()
        writeUInt16(packet, TOTAL_LENGTH_OFFSET, totalLength)
        packet[TTL_OFFSET] = DEFAULT_TTL.toByte()
        packet[PROTOCOL_OFFSET] = PROTOCOL_UDP.toByte()
        sourceAddress.address.copyInto(packet, SOURCE_ADDR_OFFSET, 0, IPV4_ADDRESS_LENGTH)
        destAddress.address.copyInto(packet, DEST_ADDR_OFFSET, 0, IPV4_ADDRESS_LENGTH)
        writeUInt16(packet, CHECKSUM_OFFSET, ipv4HeaderChecksum(packet))

        val udpStart = IPV4_HEADER_LENGTH
        writeUInt16(packet, udpStart, sourcePort)
        writeUInt16(packet, udpStart + PORT_FIELD_SIZE, destPort)
        writeUInt16(packet, udpStart + UDP_LENGTH_FIELD_OFFSET, UDP_HEADER_LENGTH + payload.size)
        payload.copyInto(packet, udpStart + UDP_HEADER_LENGTH)

        return packet
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (data[offset + 1].toInt() and BYTE_MASK)

    private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr BITS_PER_BYTE) and BYTE_MASK).toByte()
        data[offset + 1] = (value and BYTE_MASK).toByte()
    }

    private fun ipv4HeaderChecksum(header: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < IPV4_HEADER_LENGTH) {
            sum += readUInt16(header, i)
            i += PORT_FIELD_SIZE
        }
        while (sum shr BITS_PER_UINT16 != 0) {
            sum = (sum and UINT16_MASK) + (sum shr BITS_PER_UINT16)
        }
        return sum.inv() and UINT16_MASK
    }
}
