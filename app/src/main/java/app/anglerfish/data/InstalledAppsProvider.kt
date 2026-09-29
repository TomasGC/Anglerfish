package app.anglerfish.data

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap

class InstalledAppsProvider(
    private val packageManager: PackageManager,
    private val selfPackageName: String,
) {
    fun queryBlockableApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { resolveInfo ->
                val appInfo = resolveInfo.activityInfo.applicationInfo
                AppCandidate(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString(),
                    isSystemApp = appInfo.isPureSystemApp(),
                )
            }
            .distinctBy { it.packageName }

        return filterUserLaunchableApps(candidates, selfPackageName)
            .map { InstalledApp(it.packageName, it.label, loadIcon(it.packageName)) }
            .sortedBy { it.label.lowercase() }
    }

    // Two apps can share a display label (rebrands, clones) -- the icon is what actually tells
    // them apart in the list. Null on failure rather than crashing the whole list over one app's
    // icon (e.g. a stale/uninstalled entry slipping through between the query above and this call).
    private fun loadIcon(packageName: String) =
        try {
            packageManager.getApplicationIcon(packageName).toBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun ApplicationInfo.isPureSystemApp(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
}
