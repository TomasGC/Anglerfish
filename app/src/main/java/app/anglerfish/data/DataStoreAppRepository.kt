package app.anglerfish.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val SELECTED_PACKAGES_KEY = stringSetPreferencesKey("selected_packages")
private val IS_ACTIVE_KEY = booleanPreferencesKey("is_active")
private val LAYOUT_KEY = stringPreferencesKey("app_list_layout")
private val HIDDEN_PACKAGES_KEY = stringSetPreferencesKey("hidden_packages")

class DataStoreAppRepository(
    private val dataStore: DataStore<Preferences>,
) : AppRepository {

    override val state: Flow<BlockingState> = dataStore.data.map { preferences ->
        BlockingState(
            selectedPackages = preferences[SELECTED_PACKAGES_KEY].orEmpty(),
            isActive = preferences[IS_ACTIVE_KEY] ?: false,
            hiddenPackages = preferences[HIDDEN_PACKAGES_KEY].orEmpty(),
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

    // An app can't be both selected for blocking and hidden from the list at once -- hiding an
    // already-selected app deselects it in the same DataStore edit, so the two fields can never
    // briefly disagree. Unhiding never restores a selection: the app returns to Not Selected.
    override suspend fun toggleHidden(packageName: String) {
        dataStore.edit { preferences ->
            val currentHidden = preferences[HIDDEN_PACKAGES_KEY].orEmpty()
            val nowHidden = packageName !in currentHidden
            preferences[HIDDEN_PACKAGES_KEY] = if (nowHidden) currentHidden + packageName else currentHidden - packageName
            if (nowHidden) {
                preferences[SELECTED_PACKAGES_KEY] = preferences[SELECTED_PACKAGES_KEY].orEmpty() - packageName
            }
        }
    }

    override suspend fun setActive(active: Boolean) {
        dataStore.edit { preferences -> preferences[IS_ACTIVE_KEY] = active }
    }

    // Falls back to LIST on a missing or corrupted stored value, rather than crashing --
    // enumValueOf would throw on any value that isn't an exact enum name.
    override val layout: Flow<AppListLayout> = dataStore.data.map { preferences ->
        AppListLayout.entries.find { it.name == preferences[LAYOUT_KEY] } ?: AppListLayout.LIST
    }

    override suspend fun setLayout(layout: AppListLayout) {
        dataStore.edit { preferences -> preferences[LAYOUT_KEY] = layout.name }
    }
}
