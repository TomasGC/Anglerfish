package app.anglerfish.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.anglerfish.R
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
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(uiState.apps, key = { it.packageName }) { app ->
                AppRow(app = app, onToggle = { onToggleApp(app.packageName) })
            }
        }
    }
}

@Composable
private fun AppRow(app: AppListItem, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = app.label, modifier = Modifier.weight(1f))
        Checkbox(checked = app.isSelected, onCheckedChange = { onToggle() })
    }
}
