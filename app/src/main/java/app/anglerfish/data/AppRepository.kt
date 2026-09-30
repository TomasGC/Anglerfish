package app.anglerfish.data

import kotlinx.coroutines.flow.Flow

interface AppRepository {
    val state: Flow<BlockingState>
    val layout: Flow<AppListLayout>
    suspend fun toggleSelection(packageName: String)
    suspend fun setActive(active: Boolean)
    suspend fun setLayout(layout: AppListLayout)
}
