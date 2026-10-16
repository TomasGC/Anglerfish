package app.anglerfish.nat

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class InitialSecrets(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

internal fun deriveInitialSecrets(destinationConnectionId: ByteArray): InitialSecrets {
    val initialSecret = hkdfExtract(INITIAL_SALT, destinationConnectionId)
    val clientInitialSecret = hkdfExpandLabel(initialSecret, "client in", ByteArray(0), HASH_LENGTH)
    return InitialSecrets(
        key = hkdfExpandLabel(clientInitialSecret, "quic key", ByteArray(0), AES_KEY_LENGTH),
        iv = hkdfExpandLabel(clientInitialSecret, "quic iv", ByteArray(0), GCM_IV_LENGTH),
        hp = hkdfExpandLabel(clientInitialSecret, "quic hp", ByteArray(0), AES_KEY_LENGTH),
    )
}

// RFC 5869 Section 2.2.
private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)

// RFC 5869 Section 2.3 -- only the single-iteration case is needed here (every length this
// file requests is well under one HMAC-SHA256 block of 32 bytes).
private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
    val t = mac.doFinal(info + byteArrayOf(FIRST_HKDF_COUNTER))
    return t.copyOf(length)
}

// RFC 8446 Section 7.1's HkdfLabel structure: u16 length, length-prefixed "tls13 "+label, then
// a length-prefixed (empty, for every label this file uses) context.
private fun hkdfExpandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
    val fullLabel = ("tls13 " + label).toByteArray(Charsets.US_ASCII)
    val hkdfLabel = byteArrayOf((length shr BITS_PER_BYTE).toByte(), length.toByte()) +
        byteArrayOf(fullLabel.size.toByte()) + fullLabel +
        byteArrayOf(context.size.toByte()) + context
    return hkdfExpand(secret, hkdfLabel, length)
}

internal data class ParsedLongHeader(val dcid: ByteArray, val pnOffset: Int, val payloadLength: Int)

// RFC 9000 Section 17.2.2 (Initial Packet) field layout. Returns null for anything that isn't
// long enough to contain a full prefix, isn't a long header, isn't QUIC v1, or isn't packet
// type Initial (0x00) -- all fail-open per this feature's spec, never a thrown exception.
@Suppress("ReturnCount")
internal fun parseLongHeaderPrefix(packet: ByteArray): ParsedLongHeader? {
    if (packet.size < MIN_LONG_HEADER_PREFIX) return null
    val firstByte = packet[0].toInt() and BYTE_MASK
    if (firstByte and HEADER_FORM_AND_FIXED_BIT_MASK != HEADER_FORM_AND_FIXED_BIT_MASK) return null
    if ((firstByte and LONG_PACKET_TYPE_MASK) shr LONG_PACKET_TYPE_SHIFT != INITIAL_PACKET_TYPE) return null
    var version = 0
    for (k in 1..VERSION_FIELD_SIZE) {
        version = (version shl BITS_PER_BYTE) or (packet[k].toInt() and BYTE_MASK)
    }
    if (version != QUIC_V1) return null

    var i = LONG_HEADER_FIXED_PREFIX_SIZE
    val dcidLength = packet[i].toInt() and BYTE_MASK
    i += 1
    if (packet.size < i + dcidLength) return null
    val dcid = packet.copyOfRange(i, i + dcidLength)
    i += dcidLength
    if (packet.size <= i) return null
    val scidLength = packet[i].toInt() and BYTE_MASK
    i += 1 + scidLength
    val tokenLength = readVarInt(packet, i) ?: return null
    i += tokenLength.second
    i += tokenLength.first.toInt()
    val payloadLength = readVarInt(packet, i) ?: return null
    i += payloadLength.second

    return ParsedLongHeader(dcid, pnOffset = i, payloadLength = payloadLength.first.toInt())
}

// RFC 9000 Section 16: top 2 bits of the first byte select a 1/2/4/8-byte encoding.
// Returns (value, bytesConsumed), or null if the packet is too short to contain the field.
private fun readVarInt(packet: ByteArray, offset: Int): Pair<Long, Int>? {
    if (offset >= packet.size) return null
    val first = packet[offset].toInt() and BYTE_MASK
    val length = 1 shl (first shr VARINT_LENGTH_SHIFT)
    if (packet.size < offset + length) return null
    var value = (first and VARINT_LENGTH_MASK).toLong()
    for (k in 1 until length) {
        value = (value shl BITS_PER_BYTE) or (packet[offset + k].toLong() and BYTE_MASK.toLong())
    }
    return value to length
}

internal data class UnprotectedInitial(val header: ByteArray, val packetNumber: Long, val protectedPayload: ByteArray)

// RFC 9001 Sections 5.4.1/5.4.2/5.4.3. The sample is always taken at a fixed 4-byte offset
// past pnOffset regardless of the real packet number length, precisely because that length
// isn't known until after this unmasking.
@Suppress("ReturnCount")
internal fun removeHeaderProtection(packet: ByteArray, parsed: ParsedLongHeader, hp: ByteArray): UnprotectedInitial? {
    val sampleOffset = parsed.pnOffset + ASSUMED_MAX_PACKET_NUMBER_LENGTH
    if (packet.size < sampleOffset + HEADER_PROTECTION_SAMPLE_SIZE) return null
    val sample = packet.copyOfRange(sampleOffset, sampleOffset + HEADER_PROTECTION_SAMPLE_SIZE)

    val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES"))
    }
    val mask = ecb.doFinal(sample)

    val mutable = packet.copyOf()
    mutable[0] = (mutable[0].toInt() xor (mask[0].toInt() and LONG_HEADER_PROTECTED_BITS_MASK)).toByte()
    val pnLength = (mutable[0].toInt() and PACKET_NUMBER_LENGTH_FIELD_MASK) + 1
    if (packet.size < parsed.pnOffset + parsed.payloadLength) return null
    for (k in 0 until pnLength) {
        mutable[parsed.pnOffset + k] = (mutable[parsed.pnOffset + k].toInt() xor mask[1 + k].toInt()).toByte()
    }

    val header = mutable.copyOfRange(0, parsed.pnOffset + pnLength)
    var packetNumber = 0L
    for (k in 0 until pnLength) {
        val byte = mutable[parsed.pnOffset + k].toLong() and BYTE_MASK.toLong()
        packetNumber = (packetNumber shl BITS_PER_BYTE) or byte
    }
    val protectedPayload = mutable.copyOfRange(parsed.pnOffset + pnLength, parsed.pnOffset + parsed.payloadLength)
    return UnprotectedInitial(header, packetNumber, protectedPayload)
}

private const val FIRST_HKDF_COUNTER: Byte = 1
private const val INITIAL_PACKET_TYPE = 0x00
private const val QUIC_V1 = 0x00000001
private const val MIN_LONG_HEADER_PREFIX = 7
private const val LONG_HEADER_FIXED_PREFIX_SIZE = 5 // header byte(1) + version(4), before DCID length
private const val VERSION_FIELD_SIZE = 4
private const val HEX_RADIX = 16
private const val ASSUMED_MAX_PACKET_NUMBER_LENGTH = 4
private const val HEADER_PROTECTION_SAMPLE_SIZE = 16
private const val HASH_LENGTH = 32
private const val AES_KEY_LENGTH = 16
private const val GCM_IV_LENGTH = 12
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val HEADER_FORM_AND_FIXED_BIT_MASK = 0xC0
private const val LONG_PACKET_TYPE_MASK = 0x30
private const val LONG_PACKET_TYPE_SHIFT = 4
private const val VARINT_LENGTH_SHIFT = 6
private const val VARINT_LENGTH_MASK = 0x3F
// Long header: the 4 least significant bits of byte 0 are header-protected (RFC 9001 Section 5.4).
private const val LONG_HEADER_PROTECTED_BITS_MASK = 0x0F
private const val PACKET_NUMBER_LENGTH_FIELD_MASK = 0x03

// RFC 9001 Section 5.2 -- independently verified for this plan via HMAC-SHA256(salt, dcid)
// against the Appendix A.1 worked example (20 bytes). Parsed from the hex string rather than a
// byte-literal array -- same reasoning as QuicInitialPacketTest's own hex() helper, and avoids
// per-byte MagicNumber findings on a value that is already a single named constant as a whole.
private val INITIAL_SALT = hexToBytes("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte() }
