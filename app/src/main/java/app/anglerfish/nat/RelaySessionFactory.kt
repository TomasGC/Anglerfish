package app.anglerfish.nat

import android.net.VpnService
import kotlinx.coroutines.CoroutineScope

class RelaySessionFactory(
    private val vpnService: VpnService,
    private val tunWriter: TunWriter,
    private val scope: CoroutineScope,
) : SessionFactory {
    override fun createUdp(endpoints: FlowEndpoints, onClosed: () -> Unit): UdpSession =
        UdpRelay(vpnService, endpoints, tunWriter, scope, onClosed)

    override fun createTcp(endpoints: FlowEndpoints, onClosed: () -> Unit): TcpSession =
        TcpRelay(vpnService, endpoints, tunWriter, scope, onClosed)
}
