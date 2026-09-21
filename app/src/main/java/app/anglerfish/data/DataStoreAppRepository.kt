package app.anglerfish.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val SELECTED_PACKAGES_KEY = stringSetPreferencesKey("selected_packages")
private val IS_ACTIVE_KEY = booleanPreferencesKey("is_active")

class DataStoreAppRepository(
    private val dataStore: DataStore<Preferences>,
) : AppRepository {

    override val state: Flow<BlockingState> = dataStore.data.map { preferences ->
        BlockingState(
            selectedPackages = preferences[SELECTED_PACKAGES_KEY].orEmpty(),
            isActive = preferences[IS_ACTIVE_KEY] ?: false,
        )
    }

    override suspend fun toggleSelection(packageName: String) {
        dataStore.edit { preferences ->
            val current = preferences[SELECTED_PACKAGES_KEY].orEmpty()
            preferences[SELECTED_PACKAGES_KEY] = if (packageName in current) {
                current - packageName
            } else {
                current + packageName
            }
        }
    }

    override suspend fun setActive(active: Boolean) {
        dataStore.edit { preferences -> preferences[IS_ACTIVE_KEY] = active }
    }
}
