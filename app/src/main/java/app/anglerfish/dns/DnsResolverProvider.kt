package app.anglerfish.dns

import android.net.ConnectivityManager
import android.net.Network
import java.net.InetAddress

interface DnsResolverProvider {
    fun currentResolvers(): List<InetAddress>
}

// underlyingNetwork is a supplier, not a fixed Network, so the caller can track network changes
// (e.g. Wi-Fi to cellular) over the tunnel's lifetime. How that network is obtained -- and
// whether it needs to differ from ConnectivityManager's own active network -- is #42's problem to
// solve once it actually wires this into the live tunnel; a prior attempt here to pin one via
// VpnService.Builder.setUnderlyingNetworks() was reverted (see kanban #40 entry): VpnService
// exposes no way to read that value back, so it gave a future caller nothing usable, while
// actively regressing live Wi-Fi/cellular handoff tracking and requiring a new manifest
// permission for zero benefit.
class ConnectivityManagerDnsResolverProvider(
    private val connectivityManager: ConnectivityManager,
    private val underlyingNetwork: () -> Network?,
) : DnsResolverProvider {
    override fun currentResolvers(): List<InetAddress> {
        val network = underlyingNetwork() ?: return emptyList()
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return emptyList()
        return linkProperties.dnsServers
    }
}
