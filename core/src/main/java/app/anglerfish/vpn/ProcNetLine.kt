package app.anglerfish.vpn

// Parses one data line of /proc/net/{tcp,udp} (IPv4 only -- AnglerfishVpnService.buildTunnel adds
// no IPv6 route, so no IPv6 packet ever reaches the drain loop that would call this). Columns, per
// `man 5 proc`:
//   sl  local_address rem_address   st tx_queue:rx_queue tr:tm->when retrnsmt   uid  timeout inode
// local_address/rem_address are "AAAABBBB:PPPP": 8 hex chars for the IPv4 address, in host (little-
// endian on every real Android device) byte order -- so the decoded bytes are reversed relative to
// normal dotted-decimal reading. The port is plain big-endian hex, no reversal. Confirmed against
// NetGuard's ip.c (get_uid_sub): https://github.com/M66B/NetGuard/blob/master/app/src/main/jni/netguard/ip.c
object ProcNetLine {

    data class Entry(
        val localAddress: ByteArray,
        val localPort: Int,
        val remoteAddress: ByteArray,
        val remotePort: Int,
        val uid: Int,
    )

    // Split into flat, non-nested guard-clause helpers -- five independent nullable fields in one
    // function would need five early returns plus the success case, over this project's own
    // detekt.yml ReturnCount budget (4); splitting by natural grouping (fields/local/remote shape,
    // then addresses, then ports+uid) keeps every function flat and under budget, matching how
    // TcpStateMachine/TcpTransitions are split for the same reason elsewhere in this codebase.
    fun parse(line: String): Entry? {
        val (fields, local, remote) = splitFields(line) ?: return null
        val (localAddress, remoteAddress) = decodeAddresses(local, remote) ?: return null
        val (localPort, remotePort, uid) = decodePortsAndUid(fields, local, remote) ?: return null
        return Entry(localAddress, localPort, remoteAddress, remotePort, uid)
    }

    private fun splitFields(line: String): Triple<List<String>, List<String>, List<String>>? {
        val fields = line.trim().split(WHITESPACE)
        if (fields.size <= UID_FIELD) return null
        val local = fields[LOCAL_ADDRESS_FIELD].split(":")
        val remote = fields[REMOTE_ADDRESS_FIELD].split(":")
        if (local.size != 2 || remote.size != 2) return null
        return Triple(fields, local, remote)
    }

    private fun decodeAddresses(local: List<String>, remote: List<String>): Pair<ByteArray, ByteArray>? {
        val localAddress = decodeAddress(local[0]) ?: return null
        val remoteAddress = decodeAddress(remote[0]) ?: return null
        return localAddress to remoteAddress
    }

    private fun decodePortsAndUid(
        fields: List<String>,
        local: List<String>,
        remote: List<String>,
    ): Triple<Int, Int, Int>? {
        val localPort = local[1].toIntOrNull(HEX_RADIX) ?: return null
        val remotePort = remote[1].toIntOrNull(HEX_RADIX) ?: return null
        val uid = fields[UID_FIELD].toIntOrNull() ?: return null
        return Triple(localPort, remotePort, uid)
    }

    // /proc/net/tcp's address is one 32-bit word (8 hex chars); /proc/net/tcp6's is four (32 hex
    // chars, a full 128-bit address). Each word is stored on-disk in host byte order independently
    // -- not the whole buffer reversed as one block -- confirmed against NetGuard's get_uid_sub,
    // which applies htonl() per 32-bit word for both the v4 and v6 proc files.
    private fun decodeAddress(hex: String): ByteArray? {
        val wordCount = when (hex.length) {
            IPV4_ADDRESS_HEX_LENGTH -> 1
            IPV6_ADDRESS_HEX_LENGTH -> IPV6_WORD_COUNT
            else -> return null
        }
        val bytes = ByteArray(wordCount * BYTES_PER_WORD)
        for (word in 0 until wordCount) {
            decodeWord(hex, word).copyInto(bytes, word * BYTES_PER_WORD)
        }
        return bytes
    }

    private fun decodeWord(hex: String, word: Int): ByteArray {
        val offset = word * HEX_CHARS_PER_WORD
        val wordBytes = ByteArray(BYTES_PER_WORD) { i ->
            hex.substring(offset + i * 2, offset + i * 2 + 2).toInt(HEX_RADIX).toByte()
        }
        return wordBytes.reversedArray()
    }

    // Standard IPv4-mapped IPv6 form (::ffff:a.b.c.d): 10 zero bytes, then 0xFFFF, then the real
    // IPv4 address. A dual-stack socket serving an IPv4 connection shows up this way in tcp6/udp6
    // -- its own kernel conntrack entry only exists there, never in the plain v4 file, which is why
    // ProcNetUidResolver scans both. The all-zero IPv6 wildcard ("::") is deliberately NOT treated
    // as mapped here -- that's a wildcard-match concern, not an address-extraction one.
    fun ipv4MappedAddress(address: ByteArray): ByteArray? {
        if (address.size != IPV6_ADDRESS_LENGTH) return null
        val isMapped = address.copyOfRange(0, MAPPED_PREFIX_ZERO_LENGTH).all { it == 0.toByte() } &&
            address[MAPPED_PREFIX_ZERO_LENGTH] == MAPPED_MARKER_BYTE &&
            address[MAPPED_PREFIX_ZERO_LENGTH + 1] == MAPPED_MARKER_BYTE
        return if (isMapped) address.copyOfRange(MAPPED_PREFIX_LENGTH, IPV6_ADDRESS_LENGTH) else null
    }

    // An all-zero proc-file address (4 or 16 bytes) is a listening/bound-to-all-interfaces socket,
    // not a reason to reject an otherwise-matching line -- mirrors NetGuard's own tolerance. A
    // non-mapped 16-byte address never matches, since every packet this app ever parses is IPv4
    // (Ipv4UdpPacket/Ipv4TcpPacket's own scope -- no IPv6 route on the tunnel).
    fun addressMatches(procAddress: ByteArray, packetAddress: ByteArray): Boolean {
        if (procAddress.all { it == 0.toByte() }) return true
        val normalized = if (procAddress.size == IPV6_ADDRESS_LENGTH) ipv4MappedAddress(procAddress) else procAddress
        return normalized != null && normalized.contentEquals(packetAddress)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val LOCAL_ADDRESS_FIELD = 1
    private const val REMOTE_ADDRESS_FIELD = 2
    private const val UID_FIELD = 7
    private const val HEX_RADIX = 16
    private const val IPV4_ADDRESS_HEX_LENGTH = 8
    private const val IPV6_ADDRESS_HEX_LENGTH = 32
    private const val IPV6_ADDRESS_LENGTH = 16
    private const val IPV6_WORD_COUNT = 4
    private const val BYTES_PER_WORD = 4
    private const val HEX_CHARS_PER_WORD = 8
    private const val MAPPED_PREFIX_ZERO_LENGTH = 10
    private const val MAPPED_PREFIX_LENGTH = 12
    private val MAPPED_MARKER_BYTE = 0xFF.toByte()
}
