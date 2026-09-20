package app.anglerfish

import android.app.Application
import app.anglerfish.di.AppContainer

class AnglerfishApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
