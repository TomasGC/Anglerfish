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

    // #62: stopService() never reliably triggered AnglerfishVpnService's onDestroy() (confirmed
    // on-device, reproduced even on main before #42) -- Android's own VPN-framework system
    // binding appears to keep the service alive independent of this app's own start/stop calls.
    // Uses the same ACTION_DEACTIVATE the notification's Stop button already tears down the
    // tunnel through successfully, via startService() instead of stopService() -- EXTRA_QUIT_APP
    // omitted (defaults false) so the app stays open, since the user is actively looking at it.
    override fun stop() {
        val intent = Intent(appContext, AnglerfishVpnService::class.java)
            .setAction(AnglerfishVpnService.ACTION_DEACTIVATE)
        appContext.startService(intent)
    }
}
