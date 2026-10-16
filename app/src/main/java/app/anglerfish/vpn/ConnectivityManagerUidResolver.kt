package app.anglerfish.vpn

import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import app.anglerfish.nat.TransportProtocol
import java.net.InetAddress
import java.net.InetSocketAddress

// API 29+: the officially supported way to do this. TCP and UDP only -- ConnectivityManager
// returns Process.INVALID_UID for anything else (confirmed against NetGuard's own isSupported()/
// getUidQ() gating), which matches this app's own scope: neither DnsInterceptor nor NatRelay
// handles any protocol besides TCP/UDP either.
//
// Retries a few times on a short delay: confirmed on-device (#42 final verification) that
// getConnectionOwnerUid() can return INVALID_UID for the first packet of a brand-new flow, because
// the kernel's conntrack entry for it is created asynchronously and isn't always visible to the
// very next query -- the same race NetGuard's own implementation retries around. Without this, a
// freshly-opened flow intermittently got cached (10 minutes, FlowBlockModeResolver) as "uid
// unknown" and silently dropped under AdFilterOnly, even though the uid resolves fine moments
// later. Bounded cost: at most (UID_LOOKUP_ATTEMPTS - 1) * UID_LOOKUP_RETRY_DELAY_MS blocking the
// one drain thread, and only on a flow's first packet -- every later packet hits the cache.
@RequiresApi(Build.VERSION_CODES.Q)
class ConnectivityManagerUidResolver(
    private val connectivityManager: ConnectivityManager,
) : UidResolver {
    override fun resolve(
        protocol: TransportProtocol,
        sourceAddress: InetAddress,
        sourcePort: Int,
        destAddress: InetAddress,
        destPort: Int,
    ): Int? {
        val protocolNumber = when (protocol) {
            TransportProtocol.TCP -> PROTOCOL_TCP
            TransportProtocol.UDP -> PROTOCOL_UDP
        }
        val local = InetSocketAddress(sourceAddress, sourcePort)
        val remote = InetSocketAddress(destAddress, destPort)
        return RetryingLookup.untilFound(
            attempts = UID_LOOKUP_ATTEMPTS,
            delay = { Thread.sleep(UID_LOOKUP_RETRY_DELAY_MS) },
        ) {
            val uid = connectivityManager.getConnectionOwnerUid(protocolNumber, local, remote)
            uid.takeIf { it != Process.INVALID_UID }
        }
    }

    private companion object {
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val UID_LOOKUP_ATTEMPTS = 4
        const val UID_LOOKUP_RETRY_DELAY_MS = 15L
    }
}
