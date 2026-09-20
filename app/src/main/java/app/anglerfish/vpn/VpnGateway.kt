package app.anglerfish.vpn

// Seam between the ViewModel and the real Context-based VpnController, so ViewModel tests can
// fake VPN start/stop/restart without a real Context or a running VpnService.
interface VpnGateway {
    fun needsConsent(): Boolean
    fun start(selectedPackages: Set<String>)
    fun restart(selectedPackages: Set<String>)
    fun stop()
}
