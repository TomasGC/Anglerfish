package app.anglerfish.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.anglerfish.AnglerfishApplication
import app.anglerfish.vpn.vpnConsentIntent

private enum class Screen { AppList, Blocklist }

class MainActivity : ComponentActivity() {

    private val viewModel: AppListViewModel by viewModels {
        val container = (application as AnglerfishApplication).container
        AppListViewModelFactory(
            repository = container.appRepository,
            vpnGateway = container.vpnController,
            installedApps = container.installedAppsProvider.queryBlockableApps(),
        )
    }

    private val blocklistViewModel: BlocklistViewModel by viewModels {
        BlocklistViewModelFactory((application as AnglerfishApplication).container.blocklistRepository)
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.onConsentGranted()
        }
    }

    // Activation proceeds regardless of grant/deny -- this is the only call site for
    // viewModel.onActivateClicked() so the notification prompt and the VPN consent prompt never
    // launch as two overlapping system dialogs from one tap (see onActivateClicked below).
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.onActivateClicked() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var currentScreen by rememberSaveable { mutableStateOf(Screen.AppList) }
                    when (currentScreen) {
                        Screen.AppList -> {
                            val uiState by viewModel.uiState.collectAsState(initial = AppListUiState())
                            AppListScreen(
                                uiState = uiState,
                                events = viewModel.eventFlow,
                                onToggleApp = viewModel::toggleApp,
                                onToggleHidden = viewModel::toggleHidden,
                                onActivateClicked = ::requestNotificationPermissionThenActivate,
                                onDeactivateClicked = viewModel::onDeactivateClicked,
                                onConsentRequired = ::launchConsent,
                                onSearchQueryChanged = viewModel::onSearchQueryChanged,
                                onToggleLayout = viewModel::toggleLayout,
                                onManageBlocklist = { currentScreen = Screen.Blocklist },
                            )
                        }
                        Screen.Blocklist -> {
                            BackHandler { currentScreen = Screen.AppList }
                            val userAdditions by blocklistViewModel.userAdditions.collectAsState(initial = emptySet())
                            BlocklistScreen(
                                userAdditions = userAdditions.toList().sorted(),
                                onAddDomain = blocklistViewModel::addDomain,
                                onRemoveDomain = blocklistViewModel::removeDomain,
                                onBack = { currentScreen = Screen.AppList },
                            )
                        }
                    }
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

    // Requests the notification permission and activation sequentially, never concurrently --
    // firing both system dialogs from one tap let one silently swallow the other's result
    // (observed as the VPN never establishing after a fresh install, with no visible error).
    private fun requestNotificationPermissionThenActivate() {
        val notGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notGranted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onActivateClicked()
        }
    }
}
