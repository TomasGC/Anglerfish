package app.anglerfish.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.anglerfish.R
import app.anglerfish.data.AppListLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    uiState: AppListUiState,
    events: Flow<AppListEvent>,
    onToggleApp: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
    onActivateClicked: () -> Unit,
    onDeactivateClicked: () -> Unit,
    onConsentRequired: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onToggleLayout: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val selectionEmptyMessage = stringResource(R.string.activate_empty_selection)

    LaunchedEffect(events) {
        events.collectLatest { event ->
            when (event) {
                AppListEvent.SelectionEmpty -> snackbarHostState.showSnackbar(selectionEmptyMessage)
                AppListEvent.VpnConsentRequired -> onConsentRequired()
            }
        }
    }

    val sections = rememberAppSections(uiState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onToggleLayout) {
                        if (uiState.layout == AppListLayout.LIST) {
                            Icon(
                                painter = painterResource(R.drawable.ic_grid_view),
                                contentDescription = stringResource(R.string.layout_toggle_grid),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = stringResource(R.string.layout_toggle_list),
                            )
                        }
                    }
                    Switch(
                        checked = uiState.isActive,
                        onCheckedChange = { checked ->
                            if (checked) onActivateClicked() else onDeactivateClicked()
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SearchField(query = uiState.searchQuery, onQueryChanged = onSearchQueryChanged)
            when (uiState.layout) {
                AppListLayout.LIST -> LazyColumn {
                    sections.forEach { section -> appListSection(section, onToggleApp, onToggleHidden) }
                }
                AppListLayout.GRID -> LazyVerticalGrid(columns = GridCells.Fixed(GRID_COLUMNS)) {
                    sections.forEach { section -> appGridSection(section, onToggleApp, onToggleHidden) }
                }
            }
        }
    }
}

private data class AppSection(
    val title: String,
    val apps: List<AppListItem>,
    val expanded: Boolean,
    val isHiddenSection: Boolean,
    val onToggleExpanded: () -> Unit,
)

@Composable
private fun rememberAppSections(uiState: AppListUiState): List<AppSection> {
    var selectedExpanded by rememberSaveable { mutableStateOf(true) }
    var notSelectedExpanded by rememberSaveable { mutableStateOf(true) }
    var hiddenExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedTitle = stringResource(R.string.section_selected)
    val notSelectedTitle = stringResource(R.string.section_not_selected)
    val hiddenTitle = stringResource(R.string.section_hidden)
    return listOf(
        AppSection(selectedTitle, uiState.selectedApps, selectedExpanded, isHiddenSection = false) {
            selectedExpanded = !selectedExpanded
        },
        AppSection(notSelectedTitle, uiState.notSelectedApps, notSelectedExpanded, isHiddenSection = false) {
            notSelectedExpanded = !notSelectedExpanded
        },
        AppSection(hiddenTitle, uiState.hiddenApps, hiddenExpanded, isHiddenSection = true) {
            hiddenExpanded = !hiddenExpanded
        },
    )
}

@Composable
private fun SearchField(query: String, onQueryChanged: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.search_apps_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_apps_clear))
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean, onToggleExpanded: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToHideBox(onDismissed: () -> Unit, content: @Composable () -> Unit) {
    // Returns false (never confirms the dismissed state) so the box always animates back to
    // Settled -- the hidden app leaves this list via the ViewModel's state change instead, and
    // without this, a swiped item's saved SwipeToDismissBoxValue could come back "already
    // dismissed" if the same key (packageName) is reused for the app once it's unhidden.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onDismissed()
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(text = stringResource(R.string.hide_app), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        content = {
            // SwipeToDismissBox only hides backgroundContent where the foreground actually paints
            // pixels -- AppRow/AppGridItem have no opaque background of their own, so without this
            // wrapper the "Hide" label and its tint show through around the icon/label at rest, not
            // just mid-swipe.
            Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                content()
            }
        },
    )
}

private fun LazyListScope.appListSection(
    section: AppSection,
    onToggleApp: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
) {
    item(key = "header:${section.title}") {
        SectionHeader(
            title = section.title,
            count = section.apps.size,
            expanded = section.expanded,
            onToggleExpanded = section.onToggleExpanded,
        )
    }
    if (section.expanded) {
        items(section.apps, key = { it.packageName }) { app ->
            // Hidden apps unhide via tap (same gesture as deselecting a selected app) -- swipe is
            // only ever a hide action, so a hidden row skips the swipe wrapper entirely rather than
            // offering a swipe gesture with nothing behind it to reveal.
            if (section.isHiddenSection) {
                AppRow(app = app, isHiddenSection = true, onToggle = { onToggleHidden(app.packageName) })
            } else {
                SwipeToHideBox(onDismissed = { onToggleHidden(app.packageName) }) {
                    AppRow(app = app, isHiddenSection = false, onToggle = { onToggleApp(app.packageName) })
                }
            }
        }
    }
}

private fun LazyGridScope.appGridSection(
    section: AppSection,
    onToggleApp: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
) {
    item(key = "header:${section.title}", span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(
            title = section.title,
            count = section.apps.size,
            expanded = section.expanded,
            onToggleExpanded = section.onToggleExpanded,
        )
    }
    if (section.expanded) {
        items(section.apps, key = { it.packageName }) { app ->
            if (section.isHiddenSection) {
                AppGridItem(app = app, isHiddenSection = true, onToggle = { onToggleHidden(app.packageName) })
            } else {
                SwipeToHideBox(onDismissed = { onToggleHidden(app.packageName) }) {
                    AppGridItem(app = app, isHiddenSection = false, onToggle = { onToggleApp(app.packageName) })
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppListItem, isHiddenSection: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isHiddenSection) it.clickable(onClick = onToggle) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon is the only thing that distinguishes two apps sharing a display label (rebrands,
        // clones) -- absent only if InstalledAppsProvider couldn't load one, which the row still
        // renders sanely for rather than crashing over.
        if (app.icon != null) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(APP_ICON_SIZE),
            )
        } else {
            Spacer(modifier = Modifier.size(APP_ICON_SIZE))
        }
        Spacer(modifier = Modifier.size(APP_ICON_SPACING))
        Text(text = app.label, modifier = Modifier.weight(1f))
        // A hidden app isn't selectable while hidden -- no checkbox is rendered for it at all.
        // Tapping the row unhides it instead, the same gesture as deselecting a selected app.
        if (!isHiddenSection) {
            Checkbox(checked = app.isSelected, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun AppGridItem(app: AppListItem, isHiddenSection: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(GRID_CELL_OUTER_PADDING)
            .clickable(onClick = onToggle)
            .border(
                width = GRID_SELECTION_BORDER_WIDTH,
                color = if (!isHiddenSection && app.isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(GRID_CELL_CORNER_RADIUS),
            )
            .padding(GRID_CELL_INNER_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(GRID_ICON_SIZE),
            )
        } else {
            Spacer(modifier = Modifier.size(GRID_ICON_SIZE))
        }
        Text(
            text = app.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(),
        )
    }
}

private val APP_ICON_SIZE = 40.dp
private val APP_ICON_SPACING = 12.dp

private const val GRID_COLUMNS = 2
private val GRID_ICON_SIZE = 64.dp
private val GRID_SELECTION_BORDER_WIDTH = 2.dp
private val GRID_CELL_CORNER_RADIUS = 12.dp
private val GRID_CELL_OUTER_PADDING = 4.dp
private val GRID_CELL_INNER_PADDING = 8.dp
