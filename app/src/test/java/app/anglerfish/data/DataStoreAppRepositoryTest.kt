package app.anglerfish.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

        assertEquals(mapOf("com.example.one" to BlockMode.AdFilterOnly), repository.state.first().selectedPackages)
    }

    @Test
    fun `toggleSelection removes a package that was already selected`() = runTest {
        val repository = createRepository()
        repository.toggleSelection("com.example.one")

        repository.toggleSelection("com.example.one")

        assertEquals(emptyMap<String, BlockMode>(), repository.state.first().selectedPackages)
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
        assertEquals(emptyMap<String, BlockMode>(), state.selectedPackages)
    }

    @Test
    fun `hiding one of two selected packages leaves the other selected`() = runTest {
        val repository = createRepository()
        repository.toggleSelection("com.example.one")
        repository.toggleSelection("com.example.two")

        repository.toggleHidden("com.example.one")

        val state = repository.state.first()
        assertEquals(mapOf("com.example.two" to BlockMode.AdFilterOnly), state.selectedPackages)
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
        assertEquals(emptyMap<String, BlockMode>(), state.selectedPackages)
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

    @Test
    fun `setMode changes an already-selected package's mode`() = runTest {
        val repository = createRepository()
        repository.toggleSelection("com.example.one")

        repository.setMode("com.example.one", BlockMode.FullBlock)

        assertEquals(mapOf("com.example.one" to BlockMode.FullBlock), repository.state.first().selectedPackages)
    }

    @Test
    fun `setMode on a not-yet-selected package selects it with that mode`() = runTest {
        val repository = createRepository()

        repository.setMode("com.example.one", BlockMode.FullBlock)

        assertEquals(mapOf("com.example.one" to BlockMode.FullBlock), repository.state.first().selectedPackages)
    }

    @Test
    fun `a legacy plain-package-name entry migrates to FullBlock`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("legacy.preferences_pb") },
        )
        val legacyKey = stringSetPreferencesKey("selected_packages")
        dataStore.edit { it[legacyKey] = setOf("com.example.legacy") }

        val repository = DataStoreAppRepository(dataStore)

        assertEquals(mapOf("com.example.legacy" to BlockMode.FullBlock), repository.state.first().selectedPackages)
    }

    @Test
    fun `an unrecognized mode suffix falls back to FullBlock instead of crashing`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("corrupt.preferences_pb") },
        )
        val legacyKey = stringSetPreferencesKey("selected_packages")
        dataStore.edit { it[legacyKey] = setOf("com.example.one:NOT_A_REAL_MODE") }

        val repository = DataStoreAppRepository(dataStore)

        assertEquals(
            mapOf("com.example.one" to BlockMode.FullBlock),
            repository.state.first().selectedPackages,
        )
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
}
