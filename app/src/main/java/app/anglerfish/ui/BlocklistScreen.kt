package app.anglerfish.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.anglerfish.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocklistScreen(
    userAdditions: List<String>,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit,
    onBack: () -> Unit,
) {
    var newDomain by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blocklist_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.blocklist_add_placeholder)) },
                    singleLine = true,
                )
                IconButton(onClick = {
                    onAddDomain(newDomain)
                    newDomain = ""
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.blocklist_add_action))
                }
            }
            LazyColumn {
                items(userAdditions, key = { it }) { domain ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = domain, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemoveDomain(domain) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.blocklist_remove_action),
                            )
                        }
                    }
                }
            }
        }
    }
}
