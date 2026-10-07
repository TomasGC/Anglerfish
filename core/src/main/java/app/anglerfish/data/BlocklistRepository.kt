package app.anglerfish.data

import kotlinx.coroutines.flow.Flow

interface BlocklistRepository {
    val userAdditions: Flow<Set<String>>
    suspend fun addDomain(domain: String)
    suspend fun removeDomain(domain: String)
    suspend fun isBlocked(domain: String): Boolean
    suspend fun refreshIfStale()
}
