package app.anglerfish.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DataStoreAppRepositoryContractTest : AppRepositoryContractTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Cancelled in tearDown(), before TemporaryFolder's own @Rule cleanup runs -- see
    // DataStoreAppRepositoryTest's identical field for the Windows file-handle hang this prevents.
    private val dataStoreScope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    override fun createRepository(): AppRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("contract.preferences_pb") },
        )
        return DataStoreAppRepository(dataStore)
    }
}
