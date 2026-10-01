package app.anglerfish.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals

// Verifies FakeAppRepository (used throughout AppListViewModelTest) and DataStoreAppRepository
// satisfy the same behavior contract, so the two can't silently drift the way they did twice in
// one session while building per-app BlockMode (see issue #38's kanban entry) -- without needing
// a ViewModel or any Dispatchers.Main substitution in the loop at all.
abstract class AppRepositoryContractTest {

    abstract fun createRepository(): AppRepository

    @Test
    fun `toggleSelection defaults a newly selected package to AdFilterOnly`() = runTest {
        val repository = createRepository()

        repository.toggleSelection("com.example.one")

        assertEquals(mapOf("com.example.one" to BlockMode.AdFilterOnly), repository.state.first().selectedPackages)
    }

    @Test
    fun `setMode on a hidden package unhides it`() = runTest {
        val repository = createRepository()
        repository.toggleHidden("com.example.one")

        repository.setMode("com.example.one", BlockMode.FullBlock)

        val state = repository.state.first()
        assertEquals(mapOf("com.example.one" to BlockMode.FullBlock), state.selectedPackages)
        assertEquals(emptySet<String>(), state.hiddenPackages)
    }

    @Test
    fun `hiding a selected package deselects it`() = runTest {
        val repository = createRepository()
        repository.toggleSelection("com.example.one")

        repository.toggleHidden("com.example.one")

        val state = repository.state.first()
        assertEquals(emptyMap<String, BlockMode>(), state.selectedPackages)
        assertEquals(setOf("com.example.one"), state.hiddenPackages)
    }
}
