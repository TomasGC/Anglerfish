package app.anglerfish.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
                    items(uiState.apps, key = { it.packageName }) { app ->
                        AppRow(app = app, onToggle = { onToggleApp(app.packageName) })
                    }
                }
                AppListLayout.GRID -> LazyVerticalGrid(columns = GridCells.Fixed(GRID_COLUMNS)) {
                    items(uiState.apps, key = { it.packageName }) { app ->
                        AppGridItem(app = app, onToggle = { onToggleApp(app.packageName) })
                    }
                }
            }
        }
    }
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
private fun AppRow(app: AppListItem, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        Checkbox(checked = app.isSelected, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun AppGridItem(app: AppListItem, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(GRID_CELL_OUTER_PADDING)
            .clickable(onClick = onToggle)
            .border(
                width = GRID_SELECTION_BORDER_WIDTH,
                color = if (app.isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
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
