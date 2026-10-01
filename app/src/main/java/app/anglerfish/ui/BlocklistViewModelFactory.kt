package app.anglerfish.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.anglerfish.data.BlocklistRepository

class BlocklistViewModelFactory(
    private val repository: BlocklistRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BlocklistViewModel(repository) as T
}
