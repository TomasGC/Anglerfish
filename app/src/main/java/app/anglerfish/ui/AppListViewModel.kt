package app.anglerfish.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.anglerfish.data.AppRepository
import app.anglerfish.data.InstalledApp
import app.anglerfish.vpn.VpnGateway
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    // A process death (force-stop, low-memory kill) takes AnglerfishVpnService down with it, but
    // DataStore's persisted isActive flag survives -- without this, the switch shows "on" while
    // no tunnel is actually running, and nothing corrects it until the user manually toggles.
    // needsConsent() isn't re-checked here: consent was already granted when isActive became true.
    init {
        viewModelScope.launch {
            val state = repository.state.first()
            if (state.isActive && state.selectedPackages.isNotEmpty()) {
                vpnGateway.restart(state.selectedPackages)
            }
        }
    }

    private val searchQuery = MutableStateFlow("")

    // Filtering by label happens here, not in a separate pure function like
    // filterUserLaunchableApps -- that one is about system/self exclusion (a fixed property of
    // the installed-app set), this is live UI-driven visibility, re-evaluated on every keystroke.
    val uiState: Flow<AppListUiState> = combine(
        repository.state,
        flowOf(installedApps),
        searchQuery,
    ) { blockingState, apps, query ->
        AppListUiState(
            apps = apps
                .filter { it.label.contains(query, ignoreCase = true) }
                .map { app ->
                    AppListItem(
                        packageName = app.packageName,
                        label = app.label,
                        isSelected = app.packageName in blockingState.selectedPackages,
                        icon = app.icon,
                    )
                },
            isActive = blockingState.isActive,
            searchQuery = query,
        )
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
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
