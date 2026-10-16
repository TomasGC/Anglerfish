package app.anglerfish.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcNetLineTest {

    // Real /proc/net/tcp header line -- must be skipped by the caller, not fed to parse(). This
    // test documents that parse() itself has no opinion on it: it simply fails the field-count
    // check, same as any other malformed line.
    @Test
    fun `header line does not parse`() {
        val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt" +
            "   uid  timeout inode"
        assertNull(ProcNetLine.parse(header))
    }

    // 127.0.0.1:80 -> hex "0100007F:0050". Host-byte-order hex "0100007F" decodes to bytes
    // [0x01, 0x00, 0x00, 0x7F]; reversed, that's [0x7F, 0x00, 0x00, 0x01] = 127.0.0.1.
    @Test
    fun `decodes a real data line, reversing the address bytes but not the port`() {
        val line = "   0: 0100007F:0050 0202023F:01BB 01 00000000:00000000 00:00000000 " +
            "00000000  1000        0 12345 1 cccccccc 20 4 1 10 -1"

        val entry = ProcNetLine.parse(line)

        assertEquals(listOf(0x7F, 0x00, 0x00, 0x01), entry!!.localAddress.map { it.toInt() and 0xFF })
        assertEquals(0x0050, entry.localPort)
        assertEquals(listOf(0x3F, 0x02, 0x02, 0x02), entry.remoteAddress.map { it.toInt() and 0xFF })
        assertEquals(0x01BB, entry.remotePort)
        assertEquals(1000, entry.uid)
    }

    @Test
    fun `a line with too few fields does not parse`() {
        assertNull(ProcNetLine.parse("0: 0100007F:0050"))
    }

    @Test
    fun `a line whose address field has no colon does not parse`() {
        val line = "   0: 0100007F0050 0202023F:01BB 01 00000000:00000000 00:00000000 " +
            "00000000  1000        0 12345 1 cccccccc 20 4 1 10 -1"
        assertNull(ProcNetLine.parse(line))
    }

    // Final review (I6): /proc/net/tcp6 and udp6 carry a dual-stack IPv4 socket's real connection
    // too (its own kernel conntrack entry only exists there, not in the v4-only file), represented
    // as an IPv4-mapped IPv6 address. The 128-bit address is 4 separate 32-bit words, each stored
    // on-disk in host byte order and so independently reversed -- not the whole 16-byte buffer
    // reversed as one block. Confirmed against NetGuard's own get_uid_sub's per-word htonl loop.
    // ::ffff:127.0.0.1 and ::ffff:93.184.216.34 computed by hand from that same transform.
    @Test
    fun `decodes a tcp6-style line, reversing each 32-bit word of the 128-bit address independently`() {
        val line = "   0: 0000000000000000FFFF00000100007F:0050 0000000000000000FFFF000022D8B85D:01BB 01 " +
            "00000000:00000000 00:00000000 00000000  1000        0 12345 1 cccccccc 20 4 1 10 -1"

        val entry = ProcNetLine.parse(line)

        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 0x7F, 0x00, 0x00, 0x01),
            entry!!.localAddress.map { it.toInt() and 0xFF },
        )
        assertEquals(0x0050, entry.localPort)
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 93, 184, 216, 34),
            entry.remoteAddress.map { it.toInt() and 0xFF },
        )
        assertEquals(0x01BB, entry.remotePort)
        assertEquals(1000, entry.uid)
    }

    @Test
    fun `ipv4MappedAddress extracts the embedded IPv4 bytes from a mapped address`() {
        val mapped = (listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 93, 184, 216, 34).map { it.toByte() })
            .toByteArray()

        val extracted = ProcNetLine.ipv4MappedAddress(mapped)

        assertEquals(listOf(93, 184, 216, 34), extracted!!.map { it.toInt() and 0xFF })
    }

    @Test
    fun `ipv4MappedAddress returns null for a genuine non-mapped IPv6 address`() {
        val real = ByteArray(16) { (it + 1).toByte() }
        assertNull(ProcNetLine.ipv4MappedAddress(real))
    }

    @Test
    fun `ipv4MappedAddress returns null for the all-zero IPv6 wildcard`() {
        assertNull(ProcNetLine.ipv4MappedAddress(ByteArray(16)))
    }

    @Test
    fun `ipv4MappedAddress returns null for a short array`() {
        assertNull(ProcNetLine.ipv4MappedAddress(ByteArray(4)))
    }

    private val packetAddress = byteArrayOf(10, 0, 0, 2)

    @Test
    fun `addressMatches treats an all-zero 4-byte address as a wildcard`() {
        assertTrue(ProcNetLine.addressMatches(ByteArray(4), packetAddress))
    }

    @Test
    fun `addressMatches treats the all-zero IPv6 wildcard as matching too`() {
        assertTrue(ProcNetLine.addressMatches(ByteArray(16), packetAddress))
    }

    @Test
    fun `addressMatches is true for an exact 4-byte match`() {
        assertTrue(ProcNetLine.addressMatches(packetAddress, packetAddress))
    }

    @Test
    fun `addressMatches is false for a different 4-byte address`() {
        assertFalse(ProcNetLine.addressMatches(byteArrayOf(10, 0, 0, 3), packetAddress))
    }

    @Test
    fun `addressMatches is true for a mapped IPv6 address embedding the same IPv4 address`() {
        val mapped = (listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 10, 0, 0, 2).map { it.toByte() }).toByteArray()
        assertTrue(ProcNetLine.addressMatches(mapped, packetAddress))
    }

    @Test
    fun `addressMatches is false for a mapped IPv6 address embedding a different IPv4 address`() {
        val mapped = (listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 10, 0, 0, 3).map { it.toByte() }).toByteArray()
        assertFalse(ProcNetLine.addressMatches(mapped, packetAddress))
    }

    @Test
    fun `addressMatches is false for a genuine non-mapped IPv6 address`() {
        val real = ByteArray(16) { (it + 1).toByte() }
        assertFalse(ProcNetLine.addressMatches(real, packetAddress))
    }
}
