package app.anglerfish.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.anglerfish.data.AppListLayout
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
        repository.layout,
    ) { blockingState, apps, query, layout ->
        val filtered = apps.filter { it.label.contains(query, ignoreCase = true) }
        val (hidden, visible) = filtered.partition { it.packageName in blockingState.hiddenPackages }
        val (selected, notSelected) = visible.partition { it.packageName in blockingState.selectedPackages }
        AppListUiState(
            selectedApps = selected.map { it.toAppListItem(isSelected = true) },
            notSelectedApps = notSelected.map { it.toAppListItem(isSelected = false) },
            hiddenApps = hidden.map { it.toAppListItem(isSelected = false) },
            isActive = blockingState.isActive,
            searchQuery = query,
            layout = layout,
        )
    }

    private fun InstalledApp.toAppListItem(isSelected: Boolean) = AppListItem(
        packageName = packageName,
        label = label,
        isSelected = isSelected,
        icon = icon,
    )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun toggleLayout() {
        viewModelScope.launch {
            val current = repository.layout.first()
            repository.setLayout(if (current == AppListLayout.LIST) AppListLayout.GRID else AppListLayout.LIST)
        }
    }

    fun toggleHidden(packageName: String) {
        viewModelScope.launch {
            val before = repository.state.first()
            repository.toggleHidden(packageName)
            val after = repository.state.first()
            if (after.isActive && before.selectedPackages != after.selectedPackages) {
                if (after.selectedPackages.isEmpty()) {
                    vpnGateway.stop()
                    repository.setActive(false)
                } else {
                    vpnGateway.restart(after.selectedPackages)
                }
            }
        }
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
