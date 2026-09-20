package app.anglerfish.data

import kotlinx.coroutines.flow.Flow

interface AppRepository {
    val state: Flow<BlockingState>
    suspend fun toggleSelection(packageName: String)
    suspend fun setActive(active: Boolean)
}
