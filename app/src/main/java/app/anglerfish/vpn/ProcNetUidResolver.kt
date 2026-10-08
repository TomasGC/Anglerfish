package app.anglerfish.vpn

import app.anglerfish.nat.TransportProtocol
import java.io.File
import java.io.IOException
import java.net.InetAddress

// API 26-28 fallback (no official API exists below Q): parses /proc/net/{tcp,tcp6,udp,udp6}
// directly, same mechanism NetGuard uses today for this SDK range (raw NETLINK sockets are
// SELinux-blocked, which is why this reads the files as plain text instead).
//
// Final review (I6): the v6 files are checked FIRST, matching NetGuard's own order -- many Android
// apps use dual-stack AF_INET6 sockets even for an IPv4 connection, so that socket's own kernel
// conntrack entry exists only in tcp6/udp6 (as an IPv4-mapped address, ProcNetLine.addressMatches
// unwraps it), never in the plain v4 file. Checking only the v4 file, as this used to, misses those
// and fails every AdFilterOnly flow from such an app closed. A missing/unreadable v6 file (some
// kernel configs lack it) falls through to the v4 file rather than failing the whole lookup.
class ProcNetUidResolver : UidResolver {
    override fun resolve(
        protocol: TransportProtocol,
        sourceAddress: InetAddress,
        sourcePort: Int,
        destAddress: InetAddress,
        destPort: Int,
    ): Int? {
        val (v6Path, v4Path) = when (protocol) {
            TransportProtocol.TCP -> "/proc/net/tcp6" to "/proc/net/tcp"
            TransportProtocol.UDP -> "/proc/net/udp6" to "/proc/net/udp"
        }
        val sourceBytes = sourceAddress.address
        val destBytes = destAddress.address
        return findUid(v6Path, sourcePort, destPort, sourceBytes, destBytes)
            ?: findUid(v4Path, sourcePort, destPort, sourceBytes, destBytes)
    }

    private fun findUid(
        path: String,
        sourcePort: Int,
        destPort: Int,
        sourceBytes: ByteArray,
        destBytes: ByteArray,
    ): Int? =
        try {
            File(path).useLines { lines ->
                lines.drop(1).mapNotNull(ProcNetLine::parse).firstOrNull { entry ->
                    entry.localPort == sourcePort &&
                        (entry.remotePort == destPort || entry.remotePort == WILDCARD_PORT) &&
                        ProcNetLine.addressMatches(entry.localAddress, sourceBytes) &&
                        ProcNetLine.addressMatches(entry.remoteAddress, destBytes)
                }?.uid
            }
        } catch (_: IOException) {
            null
        }

    private companion object {
        const val WILDCARD_PORT = 0
    }
}
