package app.anglerfish.nat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SniGateTest {

    // Same builder as TlsClientHelloBufferTest -- duplicated here deliberately
    // rather than shared, since SniGateTest's own correctness must not depend on
    // importing test code from another test file (each test file is self-contained
    // per this project's conftest-free, no-shared-test-util convention).
    private fun buildClientHello(hostname: String): ByteArray {
        val hostBytes = hostname.toByteArray(Charsets.US_ASCII)
        fun u16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        fun u24(v: Int) = byteArrayOf(((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        val serverNameEntry = byteArrayOf(0) + u16(hostBytes.size) + hostBytes
        val serverNameList = u16(serverNameEntry.size) + serverNameEntry
        val sniExtension = u16(0x0000) + u16(serverNameList.size) + serverNameList
        val clientHelloBody = byteArrayOf(0x03, 0x03) + ByteArray(32) + byteArrayOf(0) +
            u16(2) + byteArrayOf(0x13, 0x01) + byteArrayOf(1, 0) + u16(sniExtension.size) + sniExtension
        val handshake = byteArrayOf(0x01) + u24(clientHelloBody.size) + clientHelloBody
        return byteArrayOf(0x16, 0x03, 0x01) + u16(handshake.size) + handshake
    }

    @Test
    fun `holds bytes while the SNI is still pending`() {
        val gate = SniGate(isBlocked = { false })
        val fullHello = buildClientHello("example.com")

        val result = gate.offer(fullHello.copyOfRange(0, 10))

        assertEquals(SniGateResult.Write(emptyList()), result)
    }

    @Test
    fun `flushes every held chunk once an allowed SNI resolves`() {
        val gate = SniGate(isBlocked = { false })
        val fullHello = buildClientHello("example.com")
        val firstHalf = fullHello.copyOfRange(0, 10)
        val secondHalf = fullHello.copyOfRange(10, fullHello.size)
        gate.offer(firstHalf)

        val result = gate.offer(secondHalf) as SniGateResult.Write

        assertEquals(listOf(firstHalf, secondHalf).map { it.toList() }, result.chunks.map { it.toList() })
    }

    @Test
    fun `writes a single chunk immediately once already allowed`() {
        val gate = SniGate(isBlocked = { false })
        gate.offer(buildClientHello("example.com"))
        val nextChunk = byteArrayOf(1, 2, 3)

        val result = gate.offer(nextChunk) as SniGateResult.Write

        assertEquals(listOf(nextChunk.toList()), result.chunks.map { it.toList() })
    }

    @Test
    fun `blocks once a matching SNI resolves and discards what was held`() {
        val gate = SniGate(isBlocked = { hostname -> hostname == "ads.example.com" })

        val result = gate.offer(buildClientHello("ads.example.com"))

        assertEquals(SniGateResult.Blocked, result)
    }

    @Test
    fun `stays Blocked for every later offer on the same flow`() {
        val gate = SniGate(isBlocked = { true })
        gate.offer(buildClientHello("ads.example.com"))

        val result = gate.offer(byteArrayOf(9, 9, 9))

        assertEquals(SniGateResult.Blocked, result)
    }

    @Test
    fun `allows non-TLS traffic through immediately as Write`() {
        val gate = SniGate(isBlocked = { true })
        val nonTls = byteArrayOf(0x17, 0x03, 0x03, 0, 1, 0)

        val result = gate.offer(nonTls) as SniGateResult.Write

        assertTrue(result.chunks.any { it.toList() == nonTls.toList() })
    }
}
