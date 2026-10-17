package app.anglerfish.nat

import java.io.ByteArrayOutputStream

sealed interface SniResult {
    data object Pending : SniResult
    data class Resolved(val hostname: String) : SniResult
    data object NotTls : SniResult
}

// Pure and stateful: accumulates fed bytes until it can resolve an SNI hostname,
// determine the bytes aren't a TLS ClientHello at all, or give up once the 16 KiB
// cap (TLS's own record size limit -- a real ClientHello this large would be
// pathological, not a real browser/app) is exceeded. Used identically by TcpRelay
// (fed raw app bytes) and the QUIC path (fed a synthetic TLS-record wrapper around
// a decrypted CRYPTO frame, see QuicInitialPacket) -- one parser, two callers.
class TlsClientHelloBuffer {
    private val accumulated = ByteArrayOutputStream()
    private var gaveUp = false

    fun feed(bytes: ByteArray): SniResult {
        if (gaveUp) return SniResult.NotTls
        accumulated.write(bytes)
        val data = accumulated.toByteArray()
        if (data.size > MAX_BUFFERED_BYTES) {
            gaveUp = true
            return SniResult.NotTls
        }
        return tryParse(data)
    }

    // Checks the content-type and handshake-type bytes as soon as each is individually
    // available, rather than waiting for the full 9-byte header -- a 6-byte non-TLS probe
    // (e.g. a UDP/QUIC-looking first chunk on a flow that turns out not to be one) must not
    // sit buffered as Pending just because it's shorter than a real ClientHello's own header.
    //
    // Final review (Important #2): once data.size >= totalNeeded, the FULL declared ClientHello
    // is already present -- any field whose own length claim would run past totalNeeded means
    // the declared lengths are internally inconsistent, not "need more bytes". Before this fix,
    // every such case returned Pending, so a malformed (or non-TLS, coincidentally-shaped) input
    // sat buffered forever instead of failing open, and the unbounded reads inside the extension
    // loop could throw a real IndexOutOfBounds/StringIndexOutOfBounds exception, corrupting the
    // caller's state (SniGate never gets a result back to act on). Every read below is now
    // explicitly bounded by totalNeeded (or extensionsEnd, itself bounded by totalNeeded), with
    // an outer catch as a last-resort backstop -- defense in depth, not the primary mechanism.
    private fun tryParse(data: ByteArray): SniResult =
        try {
            tryParseBounded(data)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            gaveUp = true
            SniResult.NotTls
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun tryParseBounded(data: ByteArray): SniResult {
        if (data.isEmpty()) return SniResult.Pending
        if (data[0] != TLS_HANDSHAKE_CONTENT_TYPE) return giveUpAsNotTls()
        if (data.size < HANDSHAKE_TYPE_OFFSET + 1) return SniResult.Pending
        if (data[HANDSHAKE_TYPE_OFFSET] != CLIENT_HELLO_HANDSHAKE_TYPE) return giveUpAsNotTls()
        if (data.size < RECORD_AND_HANDSHAKE_HEADER_SIZE) return SniResult.Pending
        val handshakeBodyLength = u24(data, HANDSHAKE_LENGTH_OFFSET)
        val totalNeeded = CLIENT_HELLO_BODY_OFFSET + handshakeBodyLength
        if (data.size < totalNeeded) return SniResult.Pending

        var i = CLIENT_HELLO_BODY_OFFSET + LEGACY_VERSION_SIZE + RANDOM_SIZE
        if (i >= totalNeeded) return giveUpAsNotTls()
        val sessionIdLength = data[i].toInt() and BYTE_MASK
        i += 1 + sessionIdLength
        if (i + 2 > totalNeeded) return giveUpAsNotTls()
        val cipherSuitesLength = u16(data, i)
        i += 2 + cipherSuitesLength
        if (i >= totalNeeded) return giveUpAsNotTls()
        val compressionMethodsLength = data[i].toInt() and BYTE_MASK
        i += 1 + compressionMethodsLength
        if (i + 2 > totalNeeded) return giveUpAsNotTls()
        val extensionsLength = u16(data, i)
        i += 2
        val extensionsEnd = i + extensionsLength
        if (extensionsEnd > totalNeeded) return giveUpAsNotTls()

        while (i < extensionsEnd) {
            if (i + EXTENSION_HEADER_SIZE > extensionsEnd) return giveUpAsNotTls()
            val extensionType = u16(data, i)
            val extensionLength = u16(data, i + 2)
            if (i + EXTENSION_HEADER_SIZE + extensionLength > extensionsEnd) return giveUpAsNotTls()
            if (extensionType == SERVER_NAME_EXTENSION_TYPE) {
                if (i + SERVER_NAME_DATA_OFFSET > extensionsEnd) return giveUpAsNotTls()
                val nameLength = u16(data, i + SERVER_NAME_LENGTH_OFFSET)
                val nameStart = i + SERVER_NAME_DATA_OFFSET
                if (nameStart + nameLength > extensionsEnd) return giveUpAsNotTls()
                gaveUp = true
                return SniResult.Resolved(String(data, nameStart, nameLength, Charsets.US_ASCII))
            }
            i += EXTENSION_HEADER_SIZE + extensionLength
        }
        return giveUpAsNotTls()
    }

    private fun giveUpAsNotTls(): SniResult {
        gaveUp = true
        return SniResult.NotTls
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (data[offset + 1].toInt() and BYTE_MASK)

    private fun u24(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and BYTE_MASK) shl (BITS_PER_BYTE * 2)) or
            ((data[offset + 1].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
            (data[offset + 2].toInt() and BYTE_MASK)

    private companion object {
        const val MAX_BUFFERED_BYTES = 16 * 1024
        const val HANDSHAKE_TYPE_OFFSET = 5
        const val TLS_HANDSHAKE_CONTENT_TYPE: Byte = 0x16
        const val CLIENT_HELLO_HANDSHAKE_TYPE: Byte = 0x01
        const val RECORD_AND_HANDSHAKE_HEADER_SIZE = 9 // 5-byte record header + 4-byte handshake header
        const val HANDSHAKE_LENGTH_OFFSET = 6
        const val CLIENT_HELLO_BODY_OFFSET = 9
        const val LEGACY_VERSION_SIZE = 2
        const val RANDOM_SIZE = 32
        const val SERVER_NAME_EXTENSION_TYPE = 0x0000
        const val EXTENSION_HEADER_SIZE = 4 // extension type(2) + extension length(2)
        // Within a server_name extension's body: list length(2) + entry type(1) + name length(2)
        const val SERVER_NAME_LENGTH_OFFSET = EXTENSION_HEADER_SIZE + 2 + 1
        const val SERVER_NAME_DATA_OFFSET = EXTENSION_HEADER_SIZE + 2 + 1 + 2
        const val BYTE_MASK = 0xFF
        const val BITS_PER_BYTE = 8
    }
}
