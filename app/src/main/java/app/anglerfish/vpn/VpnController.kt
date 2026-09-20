package app.anglerfish.vpn

import android.content.Context
import android.content.Intent

class VpnController(private val appContext: Context) : VpnGateway {

    override fun needsConsent(): Boolean = vpnConsentIntent(appContext) != null

    override fun start(selectedPackages: Set<String>) {
        val intent = Intent(appContext, AnglerfishVpnService::class.java)
            .putStringArrayListExtra(
                AnglerfishVpnService.EXTRA_SELECTED_PACKAGES,
                ArrayList(selectedPackages),
            )
        appContext.startForegroundService(intent)
    }

    // Re-establishing is the same call as starting: AnglerfishVpnService always tears down its
    // existing tunnel (if any) before building a new one from whatever package set it receives.
    override fun restart(selectedPackages: Set<String>) = start(selectedPackages)

    override fun stop() {
        appContext.stopService(Intent(appContext, AnglerfishVpnService::class.java))
    }
}
