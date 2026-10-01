package app.anglerfish.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.anglerfish.data.AppRepository
import app.anglerfish.data.BlocklistRepository
import app.anglerfish.data.DataStoreAppRepository
import app.anglerfish.data.DataStoreBlocklistRepository
import app.anglerfish.data.InstalledAppsProvider
import app.anglerfish.vpn.VpnController

private val Context.dataStore by preferencesDataStore(name = "anglerfish_prefs")

// Its own DataStore file, not the shared one above -- the cached remote blocklist text is a
// couple MB, and Preferences DataStore rewrites its whole backing file on every edit. Sharing one
// file would have made every toggleApp/toggleHidden/setActive/layout change (and every
// AppRepository.state emission) pay to rewrite/reread that multi-MB blob too.
private val Context.blocklistDataStore by preferencesDataStore(name = "anglerfish_blocklist_prefs")

class AppContainer(context: Context) {
    val appRepository: AppRepository = DataStoreAppRepository(context.dataStore)
    val vpnController: VpnController = VpnController(context.applicationContext)
    val installedAppsProvider: InstalledAppsProvider = InstalledAppsProvider(
        packageManager = context.packageManager,
        selfPackageName = context.packageName,
    )
    val blocklistRepository: BlocklistRepository = DataStoreBlocklistRepository(
        dataStore = context.blocklistDataStore,
    )
}
