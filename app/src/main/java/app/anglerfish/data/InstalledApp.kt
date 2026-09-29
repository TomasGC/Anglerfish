package app.anglerfish.data

import android.graphics.Bitmap

// icon defaults to null so tests that don't care about it (Bitmap isn't constructable outside a
// real Android runtime) can keep building InstalledApp with just packageName/label.
// InstalledAppsProvider always supplies a real one in production.
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap? = null,
)
