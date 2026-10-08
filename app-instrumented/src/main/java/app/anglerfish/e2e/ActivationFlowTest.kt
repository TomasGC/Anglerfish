package app.anglerfish.e2e

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.anglerfish.AnglerfishApplication
import app.anglerfish.data.BlockMode
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
        composeRule.ensureDeactivated()
        selectTestApp()
        composeRule.onNode(hasRole(Role.Switch)).performClick()
        acceptSystemPrompts()

        composeRule.waitUntil(TUNNEL_TIMEOUT_MS) { vpnTunnelExists() }

        assertTrue(vpnTunnelExists())
    }

    // The emulator's launcher apps are all pure system apps, which the app list hides, so there
    // is no checkbox to click. Selecting through the repository is the same state change the
    // checkbox makes; the switch still goes through the real activation path. setMode (an upsert),
    // not toggleSelection, so this stays correct regardless of whether a prior test in the same
    // instrumentation run already selected this same stand-in package.
    private fun selectTestApp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AnglerfishApplication
        runBlocking { app.container.appRepository.setMode(TEST_PACKAGE, BlockMode.AdFilterOnly) }
    }

    private companion object {
        const val TEST_PACKAGE = "com.android.settings"
    }
}
