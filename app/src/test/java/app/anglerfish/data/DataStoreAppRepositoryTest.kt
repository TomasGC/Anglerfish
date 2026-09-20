package app.anglerfish.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAppRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createRepository(): DataStoreAppRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob()),
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
    fun `state starts empty and inactive`() = runTest {
        val repository = createRepository()

        val state = repository.state.first()

        assertEquals(BlockingState(), state)
    }
}
