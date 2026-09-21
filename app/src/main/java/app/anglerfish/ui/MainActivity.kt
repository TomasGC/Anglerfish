package app.anglerfish.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import app.anglerfish.AnglerfishApplication
import app.anglerfish.vpn.vpnConsentIntent

class MainActivity : ComponentActivity() {

    private val viewModel: AppListViewModel by viewModels {
        val container = (application as AnglerfishApplication).container
        AppListViewModelFactory(
            repository = container.appRepository,
            vpnGateway = container.vpnController,
            installedApps = container.installedAppsProvider.queryBlockableApps(),
        )
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.onConsentGranted()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: activation still proceeds even if the notification permission is denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val uiState by viewModel.uiState.collectAsState(initial = AppListUiState())
                    AppListScreen(
                        uiState = uiState,
                        events = viewModel.eventFlow,
                        onToggleApp = viewModel::toggleApp,
                        onActivateClicked = {
                            requestNotificationPermissionIfNeeded()
                            viewModel.onActivateClicked()
                        },
                        onDeactivateClicked = viewModel::onDeactivateClicked,
                        onConsentRequired = ::launchConsent,
                    )
                }
            }
        }
    }

    private fun launchConsent() {
        val intent = vpnConsentIntent(this)
        if (intent != null) {
            consentLauncher.launch(intent)
        } else {
            viewModel.onConsentGranted()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
