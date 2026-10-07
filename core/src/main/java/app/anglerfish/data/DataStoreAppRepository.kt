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
private const val MODE_SEPARATOR = ':'

// Self-migrating: a pre-existing install's plain package-name entries (no ":MODE" suffix, from
// before BlockMode existed) decode as FullBlock, preserving their current effective behavior
// rather than silently switching them to AdFilterOnly. An unrecognized suffix (a future enum
// value this version doesn't know) falls back the same way instead of crashing, same defensive
// posture as this file's existing AppListLayout fallback below.
private fun decodeSelection(raw: Set<String>?): Map<String, BlockMode> =
    raw.orEmpty().associate { entry ->
        val separatorIndex = entry.lastIndexOf(MODE_SEPARATOR)
        if (separatorIndex == -1) {
            entry to BlockMode.FullBlock
        } else {
            val packageName = entry.substring(0, separatorIndex)
            val modeName = entry.substring(separatorIndex + 1)
            val mode = BlockMode.entries.find { it.name == modeName } ?: BlockMode.FullBlock
            packageName to mode
        }
    }

private fun encodeSelection(selectedPackages: Map<String, BlockMode>): Set<String> =
    selectedPackages.map { (packageName, mode) -> "$packageName$MODE_SEPARATOR${mode.name}" }.toSet()

class DataStoreAppRepository(
    private val dataStore: DataStore<Preferences>,
) : AppRepository {

    override val state: Flow<BlockingState> = dataStore.data.map { preferences ->
        BlockingState(
            selectedPackages = decodeSelection(preferences[SELECTED_PACKAGES_KEY]),
            isActive = preferences[IS_ACTIVE_KEY] ?: false,
            hiddenPackages = preferences[HIDDEN_PACKAGES_KEY].orEmpty(),
        )
    }

    override suspend fun toggleSelection(packageName: String) {
        dataStore.edit { preferences ->
            val current = decodeSelection(preferences[SELECTED_PACKAGES_KEY])
            val updated = if (packageName in current) {
                current - packageName
            } else {
                current + (packageName to BlockMode.AdFilterOnly)
            }
            preferences[SELECTED_PACKAGES_KEY] = encodeSelection(updated)
        }
    }

    // Mirrors toggleHidden's mutual-exclusion invariant from the other direction: setting a mode
    // means the package should now be visible and blocked, so it can't stay hidden.
    override suspend fun setMode(packageName: String, mode: BlockMode) {
        dataStore.edit { preferences ->
            val current = decodeSelection(preferences[SELECTED_PACKAGES_KEY])
            preferences[SELECTED_PACKAGES_KEY] = encodeSelection(current + (packageName to mode))
            preferences[HIDDEN_PACKAGES_KEY] = preferences[HIDDEN_PACKAGES_KEY].orEmpty() - packageName
        }
    }

    // An app can't be both selected for blocking and hidden from the list at once -- hiding an
    // already-selected app deselects it in the same DataStore edit, so the two fields can never
    // briefly disagree. Unhiding never restores a selection: the app returns to Not Selected.
    override suspend fun toggleHidden(packageName: String) {
        dataStore.edit { preferences ->
            val currentHidden = preferences[HIDDEN_PACKAGES_KEY].orEmpty()
            val nowHidden = packageName !in currentHidden
            preferences[HIDDEN_PACKAGES_KEY] = if (nowHidden) {
                currentHidden + packageName
            } else {
                currentHidden - packageName
            }
            if (nowHidden) {
                val current = decodeSelection(preferences[SELECTED_PACKAGES_KEY])
                preferences[SELECTED_PACKAGES_KEY] = encodeSelection(current - packageName)
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
