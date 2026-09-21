package app.anglerfish.ui

data class AppListUiState(
    val apps: List<AppListItem> = emptyList(),
    val isActive: Boolean = false,
)

data class AppListItem(
    val packageName: String,
    val label: String,
    val isSelected: Boolean,
)

sealed interface AppListEvent {
    data object SelectionEmpty : AppListEvent
    data object VpnConsentRequired : AppListEvent
}
