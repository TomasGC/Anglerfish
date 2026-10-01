package app.anglerfish.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAppRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Cancelled in tearDown(), which JUnit4 runs before TemporaryFolder's own @Rule cleanup --
    // an un-cancelled scope leaves DataStore's internal write-actor coroutine alive past the test,
    // which can race TemporaryFolder deleting a file the actor still holds open (Windows can't
    // delete an open file, unlike POSIX) and, across a whole suite, accumulate into exactly the
    // kind of hang this was written to prevent.
    private val dataStoreScope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private fun createRepository(): DataStoreAppRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("test.preferences_pb") },
        )
        return DataStoreAppRepository(dataStore)
    }

    @Test
    fun `toggleSelection adds a package that was not selected`() = runTest {
        val repository = createRepository()

        repository.toggleSelection("com.example.one")

        assertEquals(setOf("com.example.one"), repository.state.first().selectedPackages)
    }

    @Test
    fun `toggleSelection removes a package that was already selected`() = runTest {
        val repository = createRepository()
        repository.toggleSelection("com.example.one")

        repository.toggleSelection("com.example.one")

        assertEquals(emptySet<String>(), repository.state.first().selectedPackages)
    }

    @Test
    fun `setActive persists the active flag`() = runTest {
        val repository = createRepository()

        repository.setActive(true)

        assertEquals(true, repository.state.first().isActive)
    }

    @Test
    fun `toggleHidden adds a package that was not hidden`() = runTest {
        val repository = createRepository()

        repository.toggleHidden("com.example.one")

        assertEquals(setOf("com.example.one"), repository.state.first().hiddenPackages)
    }

    @Test
    fun `toggleHidden removes a package that was already hidden`() = runTest {
        val repository = createRepository()
        repository.toggleHidden("com.example.one")

        repository.toggleHidden("com.example.one")

        assertEquals(emptySet<String>(), repository.state.first().hiddenPackages)
    }

    @Test
    fun `hiding a selected package also deselects it`() = runTest {
        val repository = createRepository()
        repository.toggleSelection("com.example.one")

        repository.toggleHidden("com.example.one")

        val state = repository.state.first()
        assertEquals(setOf("com.example.one"), state.hiddenPackages)
        assertEquals(emptySet<String>(), state.selectedPackages)
    }

    @Test
    fun `hiding one of two selected packages leaves the other selected`() = runTest {
        val repository = createRepository()
        repository.toggleSelection("com.example.one")
        repository.toggleSelection("com.example.two")

        repository.toggleHidden("com.example.one")

        val state = repository.state.first()
        assertEquals(setOf("com.example.two"), state.selectedPackages)
        assertEquals(setOf("com.example.one"), state.hiddenPackages)
    }

    @Test
    fun `unhiding a package does not restore its selection`() = runTest {
        val repository = createRepository()
        repository.toggleSelection("com.example.one")
        repository.toggleHidden("com.example.one")

        repository.toggleHidden("com.example.one")

        val state = repository.state.first()
        assertEquals(emptySet<String>(), state.hiddenPackages)
        assertEquals(emptySet<String>(), state.selectedPackages)
    }

    @Test
    fun `state starts empty and inactive`() = runTest {
        val repository = createRepository()

        val state = repository.state.first()

        assertEquals(BlockingState(), state)
    }

    @Test
    fun `layout defaults to LIST`() = runTest {
        val repository = createRepository()

        assertEquals(AppListLayout.LIST, repository.layout.first())
    }

    @Test
    fun `setLayout persists the chosen layout`() = runTest {
        val repository = createRepository()

        repository.setLayout(AppListLayout.GRID)

        assertEquals(AppListLayout.GRID, repository.layout.first())
    }
}
