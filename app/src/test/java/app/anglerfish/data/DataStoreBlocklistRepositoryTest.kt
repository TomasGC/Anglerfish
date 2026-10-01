package app.anglerfish.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreBlocklistRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Cancelled in tearDown(), before TemporaryFolder's own @Rule cleanup runs -- see
    // DataStoreAppRepositoryTest's identical field for the Windows file-handle hang this prevents.
    private val dataStoreScope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private class FakeBlocklistFetcher(private val response: String?) : BlocklistFetcher {
        var callCount = 0
            private set
        override fun fetch(url: String): String? {
            callCount++
            return response
        }
    }

    private fun createDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = dataStoreScope,
        produceFile = { tempFolder.newFile("blocklist.preferences_pb") },
    )

    private fun createRepository(
        dataStore: DataStore<Preferences> = createDataStore(),
        fetcher: BlocklistFetcher = FakeBlocklistFetcher(response = null),
    ): DataStoreBlocklistRepository = DataStoreBlocklistRepository(dataStore, fetcher)

    private suspend fun seedRemoteCache(dataStore: DataStore<Preferences>, hostsFileBody: String) {
        val remoteCacheKey = stringPreferencesKey("blocklist_remote_cache")
        dataStore.edit { preferences -> preferences[remoteCacheKey] = hostsFileBody }
    }

    @Test
    fun `isBlocked returns true for a domain in the remote cache`() = runTest {
        val dataStore = createDataStore()
        seedRemoteCache(dataStore, "0.0.0.0 doubleclick.net")
        val repository = createRepository(dataStore = dataStore)

        assertTrue(repository.isBlocked("doubleclick.net"))
    }

    @Test
    fun `isBlocked returns true for a subdomain of a remote cache entry`() = runTest {
        val dataStore = createDataStore()
        seedRemoteCache(dataStore, "0.0.0.0 doubleclick.net")
        val repository = createRepository(dataStore = dataStore)

        assertTrue(repository.isBlocked("ads.doubleclick.net"))
    }

    @Test
    fun `isBlocked returns false for an unrelated domain`() = runTest {
        val dataStore = createDataStore()
        seedRemoteCache(dataStore, "0.0.0.0 doubleclick.net")
        val repository = createRepository(dataStore = dataStore)

        assertEquals(false, repository.isBlocked("example.com"))
    }

    @Test
    fun `isBlocked returns false before any successful fetch has ever happened and no user additions`() = runTest {
        val repository = createRepository()

        assertEquals(false, repository.isBlocked("example.com"))
    }

    @Test
    fun `addDomain makes isBlocked return true for that domain`() = runTest {
        val repository = createRepository()

        repository.addDomain("mytracker.example")

        assertTrue(repository.isBlocked("mytracker.example"))
    }

    @Test
    fun `addDomain on a domain already covered by the remote cache does not break isBlocked`() = runTest {
        val dataStore = createDataStore()
        seedRemoteCache(dataStore, "0.0.0.0 doubleclick.net")
        val repository = createRepository(dataStore = dataStore)

        repository.addDomain("doubleclick.net")

        assertEquals(setOf("doubleclick.net"), repository.userAdditions.first())
        assertTrue(repository.isBlocked("doubleclick.net"))
    }

    @Test
    fun `removeDomain removes a domain from userAdditions`() = runTest {
        val repository = createRepository()
        repository.addDomain("mytracker.example")

        repository.removeDomain("mytracker.example")

        assertEquals(emptySet<String>(), repository.userAdditions.first())
        assertEquals(false, repository.isBlocked("mytracker.example"))
    }

    @Test
    fun `refreshIfStale fetches and caches on the very first call`() = runTest {
        val fetcher = FakeBlocklistFetcher("0.0.0.0 fetched.example")
        val repository = createRepository(fetcher = fetcher)

        repository.refreshIfStale()

        assertEquals(1, fetcher.callCount)
        assertTrue(repository.isBlocked("fetched.example"))
    }

    @Test
    fun `refreshIfStale does not fetch again within the interval`() = runTest {
        val fetcher = FakeBlocklistFetcher("0.0.0.0 fetched.example")
        val repository = createRepository(fetcher = fetcher)

        repository.refreshIfStale()
        repository.refreshIfStale()

        assertEquals(1, fetcher.callCount)
    }

    @Test
    fun `refreshIfStale keeps the existing cache when the fetch fails`() = runTest {
        val dataStore = createDataStore()
        val lastFetchKey = longPreferencesKey("blocklist_last_fetch_timestamp")
        val remoteCacheKey = stringPreferencesKey("blocklist_remote_cache")
        dataStore.edit { preferences ->
            preferences[lastFetchKey] = 0L
            preferences[remoteCacheKey] = "0.0.0.0 already-cached.example"
        }
        val failingFetcher = FakeBlocklistFetcher(response = null)
        val repository = createRepository(dataStore = dataStore, fetcher = failingFetcher)

        repository.refreshIfStale()

        assertEquals(1, failingFetcher.callCount)
        assertTrue(repository.isBlocked("already-cached.example"))
    }

    @Test
    fun `refreshIfStale does not replace the cache when the fetched body parses to nothing`() = runTest {
        val dataStore = createDataStore()
        val lastFetchKey = longPreferencesKey("blocklist_last_fetch_timestamp")
        val remoteCacheKey = stringPreferencesKey("blocklist_remote_cache")
        dataStore.edit { preferences ->
            preferences[lastFetchKey] = 0L
            preferences[remoteCacheKey] = "0.0.0.0 already-cached.example"
        }
        val garbledFetcher = FakeBlocklistFetcher("<html>captive portal</html>")
        val repository = createRepository(dataStore = dataStore, fetcher = garbledFetcher)

        repository.refreshIfStale()

        assertEquals(1, garbledFetcher.callCount)
        assertTrue(repository.isBlocked("already-cached.example"))
    }
}
