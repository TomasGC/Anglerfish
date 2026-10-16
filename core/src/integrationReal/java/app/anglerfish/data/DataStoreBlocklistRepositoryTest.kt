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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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

    // Separate from dataStoreScope (which only backs the DataStore's own internal actor) --
    // this one backs DataStoreBlocklistRepository's own reactive-cache collector. Routed through
    // createRepository() so every existing test in this file gets a cancellable scope with no
    // per-test-body changes, rather than silently leaking one on the class's own default.
    private val repositoryScope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        repositoryScope.cancel()
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
    ): DataStoreBlocklistRepository = DataStoreBlocklistRepository(dataStore, fetcher, repositoryScope)

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

    // Final review (I1): isBlocked caches the parsed remote list (re-parsing a multi-hundred-
    // thousand-line hosts file on every single DNS query was the #39 deferral, now a live per-query
    // cost under #42) -- this proves the cache still reflects a changed remote value on the SAME
    // repository instance, not a stale snapshot from the first call.
    @Test
    fun `isBlocked reflects a remote cache change on a later call, not a stale cached parse`() = runTest {
        val dataStore = createDataStore()
        seedRemoteCache(dataStore, "0.0.0.0 first.example")
        val repository = createRepository(dataStore = dataStore)
        assertTrue(repository.isBlocked("first.example"))

        seedRemoteCache(dataStore, "0.0.0.0 second.example")

        assertEquals(false, repository.isBlocked("first.example"))
        assertTrue(repository.isBlocked("second.example"))
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

    // Final review (Important #3): the original version of this test passed trivially, since
    // nothing was ever seeded into the DataStore -- it would have passed even if the reactive
    // collector ran synchronously. Seeding first and holding the repository's own collector
    // coroutine paused on a StandardTestDispatcher (which runs nothing until explicitly advanced)
    // proves the real cold-start window: data already exists in the DataStore, but isBlockedNow
    // still returns false until the collector actually gets to run.
    @Test
    fun `isBlockedNow returns false before the reactive collector has run, even with data already seeded`() =
        runBlocking {
            val dataStore = createDataStore()
            seedRemoteCache(dataStore, "0.0.0.0 ads.example.com")
            val dispatcher = StandardTestDispatcher()
            val pausedScope = CoroutineScope(dispatcher)
            val repository = DataStoreBlocklistRepository(dataStore, FakeBlocklistFetcher(null), pausedScope)

            assertEquals(false, repository.isBlockedNow("ads.example.com"))

            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(awaitUntil { repository.isBlockedNow("ads.example.com") })
            pausedScope.cancel()
        }

    @Test
    fun `isBlockedNow tracks a changing remote cache reactively`() = runBlocking {
        // Not runTest: repositoryScope's collector runs on a real dispatcher (Dispatchers.Default,
        // same as production), independent of any virtual-time test scheduler, so there is no
        // single suspension point a test coroutine could yield at to deterministically guarantee
        // it has already run. A bounded real-time poll is the honest tool for an in-memory cache
        // whose update is driven by a genuinely concurrent background collector.
        val dataStore = createDataStore()
        val repository = createRepository(dataStore)
        seedRemoteCache(dataStore, "0.0.0.0 ads.example.com")

        assertTrue(awaitUntil { repository.isBlockedNow("ads.example.com") })
        assertEquals(false, repository.isBlockedNow("other.example.com"))
    }

    private suspend fun awaitUntil(timeoutMillis: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(10)
        }
        return condition()
    }
}
