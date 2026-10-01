package app.anglerfish

import android.app.Application
import app.anglerfish.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AnglerfishApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch { container.blocklistRepository.refreshIfStale() }
    }
}
