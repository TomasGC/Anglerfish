package app.anglerfish.vpn

class AnglerfishVpnService : android.net.VpnService() {
    companion object {
        const val EXTRA_SELECTED_PACKAGES = "selectedPackages"
    }
}
