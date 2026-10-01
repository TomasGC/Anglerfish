package app.anglerfish.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.anglerfish.data.BlocklistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class BlocklistViewModel(
    private val repository: BlocklistRepository,
) : ViewModel() {

    val userAdditions: Flow<Set<String>> = repository.userAdditions

    fun addDomain(domain: String) {
        val normalized = domain.trim().lowercase()
        if (normalized.isEmpty()) return
        viewModelScope.launch { repository.addDomain(normalized) }
    }

    fun removeDomain(domain: String) {
        viewModelScope.launch { repository.removeDomain(domain) }
    }
}
