package app.anglerfish.ui

import android.graphics.Bitmap
import app.anglerfish.data.AppListLayout

data class AppListUiState(
    val apps: List<AppListItem> = emptyList(),
    val isActive: Boolean = false,
    val searchQuery: String = "",
    val layout: AppListLayout = AppListLayout.LIST,
)

data class AppListItem(
    val packageName: String,
    val label: String,
    val isSelected: Boolean,
    val icon: Bitmap? = null,
)

sealed interface AppListEvent {
    data object SelectionEmpty : AppListEvent
    data object VpnConsentRequired : AppListEvent
}
