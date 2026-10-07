package app.anglerfish.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val USER_ADDITIONS_KEY = stringSetPreferencesKey("blocklist_user_additions")
private val REMOTE_CACHE_KEY = stringPreferencesKey("blocklist_remote_cache")
private val LAST_FETCH_KEY = longPreferencesKey("blocklist_last_fetch_timestamp")
private const val BLOCKLIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000

// No bundled snapshot: committing a multi-hundred-thousand-domain list tripped the shared CI
// large-file gate (500KB cap, even gzip-compressed), and a trimmed-down bundled subset would have
// been a second blocklist to maintain. refreshIfStale() runs unconditionally from
// Application.onCreate with lastFetch defaulting to 0, so the first real fetch always starts
// immediately on first launch -- coverage is simply empty until that fetch lands.
class DataStoreBlocklistRepository(
    private val dataStore: DataStore<Preferences>,
    private val fetcher: BlocklistFetcher = HttpBlocklistFetcher(),
) : BlocklistRepository {

    override val userAdditions: Flow<Set<String>> =
        dataStore.data.map { preferences -> preferences[USER_ADDITIONS_KEY].orEmpty() }

    override suspend fun addDomain(domain: String) {
        dataStore.edit { preferences ->
            preferences[USER_ADDITIONS_KEY] = preferences[USER_ADDITIONS_KEY].orEmpty() + domain
        }
    }

    override suspend fun removeDomain(domain: String) {
        dataStore.edit { preferences ->
            preferences[USER_ADDITIONS_KEY] = preferences[USER_ADDITIONS_KEY].orEmpty() - domain
        }
    }

    override suspend fun isBlocked(domain: String): Boolean {
        val preferences = dataStore.data.first()
        val remote = parseHostsFile(preferences[REMOTE_CACHE_KEY].orEmpty())
        val additions = preferences[USER_ADDITIONS_KEY].orEmpty()
        return isDomainBlocked(domain, remote + additions)
    }

    // Only updates the cache and timestamp on a fetch that both succeeds AND parses to something
    // non-empty -- a 200 response from a captive portal or an error page is otherwise
    // indistinguishable from a real list at the HTTP layer, and would silently replace a good
    // cache with nothing for a full day. Treating that the same as a failure (keep the old cache,
    // retry next app open) is what "never drop coverage" actually requires.
    override suspend fun refreshIfStale() {
        val lastFetch = dataStore.data.first()[LAST_FETCH_KEY] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastFetch < REFRESH_INTERVAL_MS) return
        val body = withContext(Dispatchers.IO) { fetcher.fetch(BLOCKLIST_URL) }
        if (body != null && parseHostsFile(body).isNotEmpty()) {
            dataStore.edit { preferences ->
                preferences[REMOTE_CACHE_KEY] = body
                preferences[LAST_FETCH_KEY] = now
            }
        }
    }
}
