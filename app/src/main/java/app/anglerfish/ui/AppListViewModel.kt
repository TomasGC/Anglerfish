package app.anglerfish.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.anglerfish.data.AppRepository
import app.anglerfish.data.InstalledApp
import app.anglerfish.vpn.VpnGateway
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AppListViewModel(
    private val repository: AppRepository,
    private val vpnGateway: VpnGateway,
    installedApps: List<InstalledApp>,
) : ViewModel() {

    private val events = Channel<AppListEvent>(Channel.BUFFERED)
    val eventFlow: Flow<AppListEvent> = events.receiveAsFlow()

    val uiState: Flow<AppListUiState> = repository.state.combine(flowOf(installedApps)) { blockingState, apps ->
        AppListUiState(
            apps = apps.map { app ->
                AppListItem(
                    packageName = app.packageName,
                    label = app.label,
                    isSelected = app.packageName in blockingState.selectedPackages,
                )
            },
            isActive = blockingState.isActive,
        )
    }

    fun toggleApp(packageName: String) {
        viewModelScope.launch {
            repository.toggleSelection(packageName)
            val state = repository.state.first()
            if (state.isActive) {
                if (state.selectedPackages.isEmpty()) {
                    vpnGateway.stop()
                    repository.setActive(false)
                } else {
                    vpnGateway.restart(state.selectedPackages)
                }
            }
        }
    }

    fun onActivateClicked() {
        viewModelScope.launch {
            val state = repository.state.first()
            if (state.selectedPackages.isEmpty()) {
                events.send(AppListEvent.SelectionEmpty)
                return@launch
            }
            if (vpnGateway.needsConsent()) {
                events.send(AppListEvent.VpnConsentRequired)
                return@launch
            }
            startBlocking(state.selectedPackages)
        }
    }

    fun onConsentGranted() {
        viewModelScope.launch {
            val state = repository.state.first()
            startBlocking(state.selectedPackages)
        }
    }

    fun onDeactivateClicked() {
        viewModelScope.launch {
            vpnGateway.stop()
            repository.setActive(false)
        }
    }

    private suspend fun startBlocking(selectedPackages: Set<String>) {
        vpnGateway.start(selectedPackages)
        repository.setActive(true)
    }
}
