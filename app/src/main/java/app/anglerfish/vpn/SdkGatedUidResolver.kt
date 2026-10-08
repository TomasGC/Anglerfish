package app.anglerfish.vpn

import android.net.ConnectivityManager
import android.os.Build
import app.anglerfish.nat.TransportProtocol
import java.net.InetAddress

// The one place that knows both real implementations exist -- AppContainer constructs exactly one
// UidResolver and never branches on SDK_INT itself.
class SdkGatedUidResolver(connectivityManager: ConnectivityManager) : UidResolver {
    private val delegate: UidResolver =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ConnectivityManagerUidResolver(connectivityManager)
        } else {
            ProcNetUidResolver()
        }

    override fun resolve(
        protocol: TransportProtocol,
        sourceAddress: InetAddress,
        sourcePort: Int,
        destAddress: InetAddress,
        destPort: Int,
    ): Int? = delegate.resolve(protocol, sourceAddress, sourcePort, destAddress, destPort)
}
