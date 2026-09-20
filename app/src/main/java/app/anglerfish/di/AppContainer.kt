package app.anglerfish.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.anglerfish.data.AppRepository
import app.anglerfish.data.DataStoreAppRepository
import app.anglerfish.data.InstalledAppsProvider
import app.anglerfish.vpn.VpnController

private val Context.dataStore by preferencesDataStore(name = "anglerfish_prefs")

class AppContainer(context: Context) {
    val appRepository: AppRepository = DataStoreAppRepository(context.dataStore)
    val vpnController: VpnController = VpnController(context.applicationContext)
    val installedAppsProvider: InstalledAppsProvider = InstalledAppsProvider(
        packageManager = context.packageManager,
        selfPackageName = context.packageName,
    )
}
