package app.anglerfish.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListFilterTest {

    @Test
    fun `excludes pure system apps`() {
        val settings = AppCandidate("com.android.settings", "Settings", isSystemApp = true)
        val game = AppCandidate("com.example.game", "Game", isSystemApp = false)

        val result = filterUserLaunchableApps(listOf(settings, game), selfPackageName = "app.anglerfish")

        assertEquals(listOf(game), result)
    }

    @Test
    fun `excludes anglerfish itself`() {
        val self = AppCandidate("app.anglerfish", "Anglerfish", isSystemApp = false)
        val game = AppCandidate("com.example.game", "Game", isSystemApp = false)

        val result = filterUserLaunchableApps(listOf(self, game), selfPackageName = "app.anglerfish")

        assertEquals(listOf(game), result)
    }

    @Test
    fun `keeps apps that are not flagged as system apps`() {
        val updatedSystemApp = AppCandidate("com.android.chrome", "Chrome", isSystemApp = false)

        val result = filterUserLaunchableApps(listOf(updatedSystemApp), selfPackageName = "app.anglerfish")

        assertEquals(listOf(updatedSystemApp), result)
    }

    @Test
    fun `keeps every candidate when nothing is excluded`() {
        val one = AppCandidate("com.example.one", "One", isSystemApp = false)
        val two = AppCandidate("com.example.two", "Two", isSystemApp = false)

        val result = filterUserLaunchableApps(listOf(one, two), selfPackageName = "app.anglerfish")

        assertEquals(listOf(one, two), result)
    }
}
