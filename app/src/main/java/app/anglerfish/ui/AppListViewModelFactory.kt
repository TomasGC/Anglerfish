package app.anglerfish.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.anglerfish.data.AppRepository
import app.anglerfish.data.InstalledApp
import app.anglerfish.vpn.VpnGateway

class AppListViewModelFactory(
    private val repository: AppRepository,
    private val vpnGateway: VpnGateway,
    private val installedApps: List<InstalledApp>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppListViewModel(repository, vpnGateway, installedApps) as T
}
