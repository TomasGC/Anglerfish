package app.anglerfish.e2e

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import app.anglerfish.AnglerfishApplication
import app.anglerfish.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Drives the activation flow through the real UI on a device: select an app, activate, accept the
// system prompts, and check that a VPN network now exists. Activation is observed at the system
// level, not through the switch, so a switch that flips without a tunnel would fail the test.
class ActivationFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activatingWithASelectedAppStartsTheVpnTunnel() {
        selectTestApp()
        composeRule.onNode(hasRole(Role.Switch)).performClick()
        acceptSystemPrompts()

        composeRule.waitUntil(TUNNEL_TIMEOUT_MS) { vpnTunnelExists() }

        assertTrue(vpnTunnelExists())
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    // The emulator's launcher apps are all pure system apps, which the app list hides, so there
    // is no checkbox to click. Selecting through the repository is the same state change the
    // checkbox makes; the switch still goes through the real activation path.
    private fun selectTestApp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AnglerfishApplication
        runBlocking { app.container.appRepository.toggleSelection(TEST_PACKAGE) }
    }

    private fun acceptSystemPrompts() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        listOf(NOTIFICATION_PROMPT_ALLOW, VPN_CONSENT_OK).forEach { label ->
            val button = device.findObject(UiSelector().textMatches(label))
            if (button.waitForExists(PROMPT_TIMEOUT_MS)) button.click()
        }
    }

    private fun vpnTunnelExists(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private companion object {
        const val TEST_PACKAGE = "com.android.settings"
        const val TUNNEL_TIMEOUT_MS = 15_000L
        const val PROMPT_TIMEOUT_MS = 5_000L
        const val NOTIFICATION_PROMPT_ALLOW = "(?i)autoriser|allow"
        const val VPN_CONSENT_OK = "(?i)ok"
    }
}
