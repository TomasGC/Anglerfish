package app.anglerfish.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService

// VpnService.prepare returns null once the user has already consented (Android remembers this
// per-app) -- non-null means the system consent dialog still needs to be shown.
fun vpnConsentIntent(context: Context): Intent? = VpnService.prepare(context)
