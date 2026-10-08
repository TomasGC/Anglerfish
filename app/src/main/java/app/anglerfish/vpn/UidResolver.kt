package app.anglerfish.vpn

import app.anglerfish.nat.TransportProtocol
import java.net.InetAddress

// Seam so AnglerfishVpnService's dispatch logic doesn't know which Android API level it's running
// on -- see SdkGatedUidResolver, which picks the real implementation once at construction. A `fun
// interface` so FlowBlockModeResolverTest (Task 5) can supply a plain lambda as a fake.
fun interface UidResolver {
    fun resolve(
        protocol: TransportProtocol,
        sourceAddress: InetAddress,
        sourcePort: Int,
        destAddress: InetAddress,
        destPort: Int,
    ): Int?
}
