package app.anglerfish.data

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

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
            .map { InstalledApp(it.packageName, it.label) }
            .sortedBy { it.label.lowercase() }
    }

    private fun ApplicationInfo.isPureSystemApp(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
}
