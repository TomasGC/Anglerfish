package app.anglerfish.nat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class TlsClientHelloBufferTest {

    // Builds a real, spec-correct TLS 1.2-framed ClientHello record with a
    // server_name extension carrying `hostname` -- independent of the parser
    // under test, the same round-trip-testing shape Ipv4TcpPacketTest already
    // uses for its own build/parse pair.
    private fun buildClientHello(hostname: String): ByteArray {
        val hostBytes = hostname.toByteArray(Charsets.US_ASCII)
        val serverNameEntry = ByteArrayOutputStream().apply {
            write(0) // host_name entry type
            writeU16(hostBytes.size)
            write(hostBytes)
        }.toByteArray()
        val serverNameList = ByteArrayOutputStream().apply {
            writeU16(serverNameEntry.size)
            write(serverNameEntry)
        }.toByteArray()
        val sniExtension = ByteArrayOutputStream().apply {
            writeU16(0x0000) // server_name extension type
            writeU16(serverNameList.size)
            write(serverNameList)
        }.toByteArray()
        val clientHelloBody = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03)) // legacy_version TLS 1.2
            write(ByteArray(32)) // random
            write(0) // session_id length
            writeU16(2) // cipher_suites length
            write(byteArrayOf(0x13, 0x01)) // TLS_AES_128_GCM_SHA256
            write(1) // compression_methods length
            write(0) // null compression
            writeU16(sniExtension.size)
            write(sniExtension)
        }.toByteArray()
        val handshake = ByteArrayOutputStream().apply {
            write(0x01) // ClientHello
            writeU24(clientHelloBody.size)
            write(clientHelloBody)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x16) // handshake content type
            write(byteArrayOf(0x03, 0x01)) // record legacy version
            writeU16(handshake.size)
            write(handshake)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU24(value: Int) {
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    @Test
    fun `resolves the SNI from a single-feed ClientHello`() {
        val buffer = TlsClientHelloBuffer()

        val result = buffer.feed(buildClientHello("example.com"))

        assertEquals(SniResult.Resolved("example.com"), result)
    }

    @Test
    fun `resolves the SNI when the ClientHello arrives split across three feeds`() {
        val buffer = TlsClientHelloBuffer()
        val bytes = buildClientHello("example.com")
        val third = bytes.size / 3

        val first = buffer.feed(bytes.copyOfRange(0, third))
        val second = buffer.feed(bytes.copyOfRange(third, 2 * third))
        val finalResult = buffer.feed(bytes.copyOfRange(2 * third, bytes.size))

        assertEquals(SniResult.Pending, first)
        assertEquals(SniResult.Pending, second)
        assertEquals(SniResult.Resolved("example.com"), finalResult)
    }

    @Test
    fun `is NotTls when the first bytes are not a TLS handshake record`() {
        val buffer = TlsClientHelloBuffer()

        val result = buffer.feed(byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5))

        assertEquals(SniResult.NotTls, result)
    }

    @Test
    fun `is NotTls when a real ClientHello has no server_name extension`() {
        val buffer = TlsClientHelloBuffer()
        val clientHelloBody = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03))
            write(ByteArray(32))
            write(0)
            writeU16(2)
            write(byteArrayOf(0x13, 0x01))
            write(1)
            write(0)
            writeU16(0) // zero-length extensions
        }.toByteArray()
        val handshake = ByteArrayOutputStream().apply {
            write(0x01)
            writeU24(clientHelloBody.size)
            write(clientHelloBody)
        }.toByteArray()
        val record = ByteArrayOutputStream().apply {
            write(0x16)
            write(byteArrayOf(0x03, 0x01))
            writeU16(handshake.size)
            write(handshake)
        }.toByteArray()

        val result = buffer.feed(record)

        assertEquals(SniResult.NotTls, result)
    }

    @Test
    fun `is Pending on a truncated record that could still be TLS`() {
        val buffer = TlsClientHelloBuffer()
        val full = buildClientHello("example.com")

        val result = buffer.feed(full.copyOfRange(0, 10))

        assertEquals(SniResult.Pending, result)
    }

    @Test
    fun `gives up as NotTls once buffered bytes exceed the 16 KiB cap`() {
        val buffer = TlsClientHelloBuffer()
        // Looks like the start of a real TLS handshake record but never completes --
        // must not buffer forever waiting for a ClientHello that will never arrive whole.
        val oversized = byteArrayOf(0x16, 0x03, 0x01, 0x7f, 0xff.toByte(), 0x01) + ByteArray(20_000)

        val result = buffer.feed(oversized)

        assertEquals(SniResult.NotTls, result)
    }

    // Final review finding (Important #2): once the full declared ClientHello (totalNeeded
    // bytes) is already buffered, a session-id length that claims more bytes than actually
    // remain means the declared lengths are internally inconsistent -- not "need more bytes".
    // The old code returned Pending here, so SniGate would hold a blocked-forever flow's bytes
    // indefinitely instead of failing open (this project's own stated rule, Global Constraints
    // in the plan: "never fail closed on a parse that couldn't be completed").
    @Test
    fun `is NotTls, not stuck Pending, when the full ClientHello has a session-id length that overruns it`() {
        val buffer = TlsClientHelloBuffer()
        val clientHelloBody = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03)) // legacy_version
            write(ByteArray(32)) // random
            write(200) // session_id length claims 200 bytes -- nothing like that follows
        }.toByteArray()
        val handshake = ByteArrayOutputStream().apply {
            write(0x01)
            writeU24(clientHelloBody.size) // the declared body is exactly what's present: no more is coming
            write(clientHelloBody)
        }.toByteArray()
        val record = ByteArrayOutputStream().apply {
            write(0x16)
            write(byteArrayOf(0x03, 0x01))
            writeU16(handshake.size)
            write(handshake)
        }.toByteArray()

        val result = buffer.feed(record)

        assertEquals(SniResult.NotTls, result)
    }

    // Final review finding (Important #2): an extension (or the server_name extension's own
    // inner length) that claims more bytes than the extensions block actually contains must not
    // crash with an out-of-bounds read -- it must resolve NotTls, the same fail-open outcome as
    // any other malformed ClientHello.
    @Test
    fun `is NotTls, not a crash, when the server_name extension claims a name longer than the buffer`() {
        val buffer = TlsClientHelloBuffer()
        // A server_name extension whose declared inner name length (999) vastly exceeds the
        // single byte of real data that follows it.
        val sniExtension = ByteArrayOutputStream().apply {
            writeU16(0x0000) // server_name extension type
            writeU16(5) // extension length: just enough header to look plausible
            writeU16(3) // server_name_list length
            write(0) // host_name entry type
            writeU16(999) // name length -- far beyond what's actually present
        }.toByteArray()
        val clientHelloBody = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03))
            write(ByteArray(32))
            write(0) // session_id length
            writeU16(2)
            write(byteArrayOf(0x13, 0x01))
            write(1)
            write(0)
            writeU16(sniExtension.size)
            write(sniExtension)
        }.toByteArray()
        val handshake = ByteArrayOutputStream().apply {
            write(0x01)
            writeU24(clientHelloBody.size)
            write(clientHelloBody)
        }.toByteArray()
        val record = ByteArrayOutputStream().apply {
            write(0x16)
            write(byteArrayOf(0x03, 0x01))
            writeU16(handshake.size)
            write(handshake)
        }.toByteArray()

        val result = buffer.feed(record)

        assertEquals(SniResult.NotTls, result)
    }

    // Final review finding (Important #3): proves a non-server_name extension before the real
    // one (GREASE, or any other real extension) doesn't confuse the scan -- it already shouldn't,
    // since the loop scans every extension regardless of position, but this was untested.
    @Test
    fun `resolves the SNI when server_name is not the first extension`() {
        val buffer = TlsClientHelloBuffer()
        val hostBytes = "example.com".toByteArray(Charsets.US_ASCII)
        val serverNameEntry = ByteArrayOutputStream().apply {
            write(0)
            writeU16(hostBytes.size)
            write(hostBytes)
        }.toByteArray()
        val serverNameList = ByteArrayOutputStream().apply {
            writeU16(serverNameEntry.size)
            write(serverNameEntry)
        }.toByteArray()
        val sniExtension = ByteArrayOutputStream().apply {
            writeU16(0x0000)
            writeU16(serverNameList.size)
            write(serverNameList)
        }.toByteArray()
        // A GREASE-style extension (RFC 8701): an unassigned reserved type, zero-length body.
        val greaseExtension = ByteArrayOutputStream().apply {
            writeU16(0x0a0a)
            writeU16(0)
        }.toByteArray()
        val clientHelloBody = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03))
            write(ByteArray(32))
            write(0)
            writeU16(2)
            write(byteArrayOf(0x13, 0x01))
            write(1)
            write(0)
            writeU16(greaseExtension.size + sniExtension.size)
            write(greaseExtension)
            write(sniExtension)
        }.toByteArray()
        val handshake = ByteArrayOutputStream().apply {
            write(0x01)
            writeU24(clientHelloBody.size)
            write(clientHelloBody)
        }.toByteArray()
        val record = ByteArrayOutputStream().apply {
            write(0x16)
            write(byteArrayOf(0x03, 0x01))
            writeU16(handshake.size)
            write(handshake)
        }.toByteArray()

        val result = buffer.feed(record)

        assertEquals(SniResult.Resolved("example.com"), result)
    }
}
