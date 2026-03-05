package tech.torlando.ara.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.torlando.ara.viewmodel.AraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubBrowserScreen(viewModel: AraViewModel) {
    val discoveredHubs by viewModel.discoveredHubs.collectAsState()
    val clientState by viewModel.clientState.collectAsState()
    val connectedHub by viewModel.connectedHubName.collectAsState()
    val reticulumStarted by viewModel.reticulumStarted.collectAsState()
    var showManualDialog by remember { mutableStateOf(false) }
    var manualHash by remember { mutableStateOf("") }

    val isConnected = clientState == tech.torlando.ara.rrc.ClientState.ACTIVE
    val isConnecting = clientState == tech.torlando.ara.rrc.ClientState.CONNECTING ||
            clientState == tech.torlando.ara.rrc.ClientState.AWAITING_WELCOME

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Discover Hubs")
                        if (!reticulumStarted) {
                            Text(
                                text = "Starting Reticulum...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (isConnected) {
                            Text(
                                text = "Connected to ${connectedHub ?: "hub"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (isConnected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = connectedHub ?: "Connected Hub",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        OutlinedButton(onClick = { viewModel.disconnectFromHub() }) {
                            Text("Disconnect")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = { showManualDialog = true }) {
                    Text("Enter Hash")
                }
            }

            if (discoveredHubs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Listening for hub announces...\nHubs will appear here when discovered.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(discoveredHubs) { hub ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hub.name,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = hub.hexHash.take(16) + "...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = { viewModel.connectToHub(hub.hash) },
                                    enabled = !isConnected && !isConnecting,
                                ) {
                                    Text(if (isConnecting) "Connecting..." else "Connect")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showManualDialog) {
            AlertDialog(
                onDismissRequest = {
                    showManualDialog = false
                    manualHash = ""
                },
                title = { Text("Connect to Hub") },
                text = {
                    OutlinedTextField(
                        value = manualHash,
                        onValueChange = { manualHash = it },
                        label = { Text("Hub destination hash (hex)") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val hash = parseHexHash(manualHash.trim())
                            if (hash != null) {
                                viewModel.connectToHub(hash)
                                showManualDialog = false
                                manualHash = ""
                            }
                        },
                    ) {
                        Text("Connect")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showManualDialog = false
                        manualHash = ""
                    }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

private fun parseHexHash(hex: String): ByteArray? {
    val cleaned = hex.replace(" ", "").replace(":", "").lowercase()
    if (cleaned.length % 2 != 0 || cleaned.isEmpty()) return null
    return try {
        cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (_: NumberFormatException) {
        null
    }
}
