package app.anglerfish.e2e

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector

// Shared across every instrumented e2e test that drives real activation through the UI --
// extracted so a second test doesn't duplicate ActivationFlowTest's own
// system-prompt/tunnel-detection logic.
internal fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

// Final review (C1): every test that clicks the switch needs a known starting state --
// instrumentation runs every test class in one app process, so a prior test's real,
// never-deactivated tunnel leaves the persisted isActive flag true, and the switch's checked state
// (bound to that flag) means the next test's own click would deactivate instead of activate.
// Drives to "off" through the real production path (the switch), not a direct repository write,
// which wouldn't tear down an already-running real tunnel (setActive() alone is only the persisted
// flag; stopping the real VpnService is onDeactivateClicked()'s own job).
internal fun ComposeTestRule.ensureDeactivated() {
    if (!vpnTunnelExists()) return
    onNode(hasRole(Role.Switch)).performClick()
    waitUntil(TUNNEL_TIMEOUT_MS) { !vpnTunnelExists() }
}

internal fun acceptSystemPrompts() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    listOf(NOTIFICATION_PROMPT_ALLOW, VPN_CONSENT_OK).forEach { label ->
        val button = device.findObject(UiSelector().textMatches(label))
        if (button.waitForExists(PROMPT_TIMEOUT_MS)) button.click()
    }
}

internal fun vpnTunnelExists(): Boolean {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return connectivity.allNetworks.any { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}

internal const val TUNNEL_TIMEOUT_MS = 15_000L
private const val PROMPT_TIMEOUT_MS = 5_000L
private const val NOTIFICATION_PROMPT_ALLOW = "(?i)autoriser|allow"
private const val VPN_CONSENT_OK = "(?i)ok"
