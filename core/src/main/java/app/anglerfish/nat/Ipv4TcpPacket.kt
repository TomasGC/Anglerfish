package app.anglerfish.nat

import java.net.InetAddress

// Overrides equals()/hashCode() because the generated versions compare `payload` by reference,
// not content -- a real trap for anything that asserts equality on this type (hit once already,
// see Ipv4TcpPacketTest's history).
data class Ipv4TcpSegment(
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destAddress: InetAddress,
    val destPort: Int,
    val sequenceNumber: Long,
    val ackNumber: Long,
    val syn: Boolean,
    val ack: Boolean,
    val fin: Boolean,
    val rst: Boolean,
    val psh: Boolean,
    val windowSize: Int,
    // Outbound-only: parse never fills this, so it is always null on an inbound segment.
    val mss: Int? = null,
    val payload: ByteArray,
) {
    // Grouping the non-array fields into one comparable list keeps this equals()/hashCode() pair's
    // own cyclomatic complexity low -- a flat chain of 13 `&&`-joined field comparisons trips
    // detekt's CyclomaticComplexMethod threshold.
    private fun nonPayloadFields() = listOf(
        sourceAddress, sourcePort, destAddress, destPort, sequenceNumber, ackNumber,
        syn, ack, fin, rst, psh, windowSize, mss,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ipv4TcpSegment) return false
        return nonPayloadFields() == other.nonPayloadFields() && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = HASH_PRIME * nonPayloadFields().hashCode() + payload.contentHashCode()
}

private const val IPV4_HEADER_LENGTH = 20
private const val TCP_HEADER_LENGTH = 20
private const val MSS_OPTION_KIND = 2
private const val MSS_OPTION_LENGTH = 4
private const val PROTOCOL_TCP = 6
private const val IPV4_VERSION = 4
private const val BYTE_MASK = 0xFF
private const val UINT16_MASK = 0xFFFF
private const val UINT32_MASK = 0xFFFFFFFFL
private const val BITS_PER_BYTE = 8
private const val BITS_PER_UINT16 = 16
private const val BYTES_PER_UINT32 = 4
private const val VERSION_SHIFT = 4
private const val IHL_MASK = 0x0F
private const val BYTES_PER_IHL_UNIT = 4
private const val VERSION_IHL_OFFSET = 0
private const val TOTAL_LENGTH_OFFSET = 2
private const val DEFAULT_TTL = 64
private const val TTL_OFFSET = 8
private const val PROTOCOL_OFFSET = 9
private const val IPV4_CHECKSUM_OFFSET = 10
private const val SOURCE_ADDR_OFFSET = 12
private const val DEST_ADDR_OFFSET = 16
private const val IPV4_ADDRESS_LENGTH = 4
private const val IPV4_VERSION_IHL_NO_OPTIONS = 0x45
private const val DATA_OFFSET_SHIFT = 4
private const val WORDS_TO_BYTES = 4
private const val FLAG_FIN = 0x01
private const val FLAG_SYN = 0x02
private const val FLAG_RST = 0x04
private const val FLAG_PSH = 0x08
private const val FLAG_ACK = 0x10
private const val PSEUDO_HEADER_LENGTH = 12
private const val TCP_CHECKSUM_OFFSET = 16
private const val TCP_SEQ_OFFSET = 4
private const val TCP_ACK_OFFSET = 8
private const val TCP_DATA_OFFSET_BYTE = 12
private const val TCP_FLAGS_OFFSET = 13
private const val TCP_WINDOW_OFFSET = 14
private const val TCP_URGENT_POINTER_OFFSET = 18
private const val PSEUDO_DEST_OFFSET = 4
private const val PSEUDO_PROTOCOL_OFFSET = 9
private const val PSEUDO_LENGTH_OFFSET = 10
private const val FLAGS_FRAGMENT_OFFSET = 6
private const val MORE_FRAGMENTS_FLAG = 0x2000
private const val FRAGMENT_OFFSET_MASK = 0x1FFF
private const val HASH_PRIME = 31

// Mirrors Ipv4UdpPacket's shape (#40) but for TCP: hand-rolled envelope parsing/building, with a
// mandatory checksum -- unlike UDP's optional 0, a receiving kernel TCP stack silently discards a
// TCP segment with an invalid checksum, so this one can't be skipped.
object Ipv4TcpPacket {

    fun parse(raw: ByteArray): Ipv4TcpSegment? {
        if (raw.size < IPV4_HEADER_LENGTH + TCP_HEADER_LENGTH) return null

        val versionIhl = raw[VERSION_IHL_OFFSET].toInt() and BYTE_MASK
        val version = versionIhl shr VERSION_SHIFT
        val ipHeaderLength = (versionIhl and IHL_MASK) * BYTES_PER_IHL_UNIT
        val protocol = raw[PROTOCOL_OFFSET].toInt() and BYTE_MASK
        val fragmentField = readUInt16(raw, FLAGS_FRAGMENT_OFFSET)
        val isFragment = fragmentField and MORE_FRAGMENTS_FLAG != 0 || fragmentField and FRAGMENT_OFFSET_MASK != 0
        val envelopeValid = version == IPV4_VERSION &&
            protocol == PROTOCOL_TCP &&
            ipHeaderLength >= IPV4_HEADER_LENGTH &&
            !isFragment &&
            raw.size >= ipHeaderLength + TCP_HEADER_LENGTH
        if (!envelopeValid) return null

        val totalLength = readUInt16(raw, TOTAL_LENGTH_OFFSET)
        val tcpStart = ipHeaderLength
        val dataOffsetWords = (raw[tcpStart + TCP_DATA_OFFSET_BYTE].toInt() and BYTE_MASK) shr DATA_OFFSET_SHIFT
        val tcpHeaderLength = dataOffsetWords * WORDS_TO_BYTES
        val payloadStart = tcpStart + tcpHeaderLength
        if (tcpHeaderLength < TCP_HEADER_LENGTH || totalLength < payloadStart || totalLength > raw.size) return null

        val flags = raw[tcpStart + TCP_FLAGS_OFFSET].toInt() and BYTE_MASK

        return Ipv4TcpSegment(
            sourceAddress = InetAddress.getByAddress(
                raw.copyOfRange(SOURCE_ADDR_OFFSET, SOURCE_ADDR_OFFSET + IPV4_ADDRESS_LENGTH),
            ),
            sourcePort = readUInt16(raw, tcpStart),
            destAddress = InetAddress.getByAddress(
                raw.copyOfRange(DEST_ADDR_OFFSET, DEST_ADDR_OFFSET + IPV4_ADDRESS_LENGTH),
            ),
            destPort = readUInt16(raw, tcpStart + 2),
            sequenceNumber = readUInt32(raw, tcpStart + TCP_SEQ_OFFSET),
            ackNumber = readUInt32(raw, tcpStart + TCP_ACK_OFFSET),
            syn = flags and FLAG_SYN != 0,
            ack = flags and FLAG_ACK != 0,
            fin = flags and FLAG_FIN != 0,
            rst = flags and FLAG_RST != 0,
            psh = flags and FLAG_PSH != 0,
            windowSize = readUInt16(raw, tcpStart + TCP_WINDOW_OFFSET),
            payload = raw.copyOfRange(payloadStart, totalLength),
        )
    }

    fun build(segment: Ipv4TcpSegment): ByteArray {
        val tcpHeaderLength = if (segment.mss != null) TCP_HEADER_LENGTH + MSS_OPTION_LENGTH else TCP_HEADER_LENGTH
        val totalLength = IPV4_HEADER_LENGTH + tcpHeaderLength + segment.payload.size
        val packet = ByteArray(totalLength)

        packet[VERSION_IHL_OFFSET] = IPV4_VERSION_IHL_NO_OPTIONS.toByte()
        writeUInt16(packet, TOTAL_LENGTH_OFFSET, totalLength)
        packet[TTL_OFFSET] = DEFAULT_TTL.toByte()
        packet[PROTOCOL_OFFSET] = PROTOCOL_TCP.toByte()
        segment.sourceAddress.address.copyInto(packet, SOURCE_ADDR_OFFSET, 0, IPV4_ADDRESS_LENGTH)
        segment.destAddress.address.copyInto(packet, DEST_ADDR_OFFSET, 0, IPV4_ADDRESS_LENGTH)
        writeUInt16(packet, IPV4_CHECKSUM_OFFSET, ipv4HeaderChecksum(packet))

        val tcpStart = IPV4_HEADER_LENGTH
        writeTcpHeader(packet, tcpStart, segment, tcpHeaderLength)
        segment.payload.copyInto(packet, tcpStart + tcpHeaderLength)
        val tcpSegmentBytes = packet.copyOfRange(tcpStart, packet.size)
        val checksum = tcpChecksum(segment.sourceAddress, segment.destAddress, tcpSegmentBytes)
        writeUInt16(packet, tcpStart + TCP_CHECKSUM_OFFSET, checksum)

        return packet
    }

    private fun writeTcpHeader(packet: ByteArray, tcpStart: Int, segment: Ipv4TcpSegment, tcpHeaderLength: Int) {
        writeUInt16(packet, tcpStart, segment.sourcePort)
        writeUInt16(packet, tcpStart + 2, segment.destPort)
        writeUInt32(packet, tcpStart + TCP_SEQ_OFFSET, segment.sequenceNumber)
        writeUInt32(packet, tcpStart + TCP_ACK_OFFSET, segment.ackNumber)
        packet[tcpStart + TCP_DATA_OFFSET_BYTE] = ((tcpHeaderLength / WORDS_TO_BYTES) shl DATA_OFFSET_SHIFT).toByte()
        var flags = 0
        if (segment.fin) flags = flags or FLAG_FIN
        if (segment.syn) flags = flags or FLAG_SYN
        if (segment.rst) flags = flags or FLAG_RST
        if (segment.psh) flags = flags or FLAG_PSH
        if (segment.ack) flags = flags or FLAG_ACK
        packet[tcpStart + TCP_FLAGS_OFFSET] = flags.toByte()
        writeUInt16(packet, tcpStart + TCP_WINDOW_OFFSET, segment.windowSize)
        writeUInt16(packet, tcpStart + TCP_CHECKSUM_OFFSET, 0)
        writeUInt16(packet, tcpStart + TCP_URGENT_POINTER_OFFSET, 0)
        segment.mss?.let { mss ->
            val optionStart = tcpStart + TCP_HEADER_LENGTH
            packet[optionStart] = MSS_OPTION_KIND.toByte()
            packet[optionStart + 1] = MSS_OPTION_LENGTH.toByte()
            writeUInt16(packet, optionStart + 2, mss)
        }
    }

    private fun tcpChecksum(sourceAddress: InetAddress, destAddress: InetAddress, tcpSegment: ByteArray): Int {
        val padded = if (tcpSegment.size % 2 == 0) tcpSegment else tcpSegment + byteArrayOf(0)
        val pseudoHeader = ByteArray(PSEUDO_HEADER_LENGTH + padded.size)
        sourceAddress.address.copyInto(pseudoHeader, 0, 0, IPV4_ADDRESS_LENGTH)
        destAddress.address.copyInto(pseudoHeader, PSEUDO_DEST_OFFSET, 0, IPV4_ADDRESS_LENGTH)
        pseudoHeader[PSEUDO_PROTOCOL_OFFSET] = PROTOCOL_TCP.toByte()
        writeUInt16(pseudoHeader, PSEUDO_LENGTH_OFFSET, tcpSegment.size)
        padded.copyInto(pseudoHeader, PSEUDO_HEADER_LENGTH)
        return foldedChecksum(pseudoHeader)
    }

    private fun ipv4HeaderChecksum(header: ByteArray): Int = foldedChecksum(header.copyOfRange(0, IPV4_HEADER_LENGTH))

    private fun foldedChecksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < data.size) {
            sum += readUInt16(data, i)
            i += 2
        }
        while (sum shr BITS_PER_UINT16 != 0) {
            sum = (sum and UINT16_MASK) + (sum shr BITS_PER_UINT16)
        }
        return sum.inv() and UINT16_MASK
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (data[offset + 1].toInt() and BYTE_MASK)

    private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr BITS_PER_BYTE) and BYTE_MASK).toByte()
        data[offset + 1] = (value and BYTE_MASK).toByte()
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until BYTES_PER_UINT32) {
            result = (result shl BITS_PER_BYTE) or (data[offset + i].toLong() and BYTE_MASK.toLong())
        }
        return result and UINT32_MASK
    }

    private fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
        for (i in 0 until BYTES_PER_UINT32) {
            val shift = BITS_PER_BYTE * (BYTES_PER_UINT32 - 1 - i)
            data[offset + i] = ((value shr shift) and BYTE_MASK.toLong()).toByte()
        }
    }
}
