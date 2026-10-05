package app.anglerfish.data

data class AppCandidate(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

// A "pure" system app (Android's FLAG_SYSTEM set, FLAG_UPDATED_SYSTEM_APP not set — computed in
// InstalledAppsProvider) is a background/OEM component the user never chose to install and rarely
// recognizes. Hiding it keeps the list to apps the user actually installed, while an updated
// system app (e.g. a pre-installed browser the user has since updated) behaves like an ordinary
// app and stays visible.
fun filterUserLaunchableApps(
    candidates: List<AppCandidate>,
    selfPackageName: String,
): List<AppCandidate> = candidates.filter { candidate ->
    !candidate.isSystemApp && candidate.packageName != selfPackageName
}
