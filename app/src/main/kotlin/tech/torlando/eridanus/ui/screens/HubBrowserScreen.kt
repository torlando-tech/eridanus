// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import tech.torlando.eridanus.viewmodel.EridanusViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubBrowserScreen(viewModel: EridanusViewModel) {
    val discoveredHubs by viewModel.discoveredHubs.collectAsState()
    val clientState by viewModel.clientState.collectAsState()
    val connectedHub by viewModel.connectedHubName.collectAsState()
    val reticulumStarted by viewModel.reticulumStarted.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    var showManualDialog by remember { mutableStateOf(false) }
    var manualHash by remember { mutableStateOf("") }

    val isConnected = clientState == tech.torlando.eridanus.rrc.ClientState.ACTIVE
    val isConnecting = clientState == tech.torlando.eridanus.rrc.ClientState.CONNECTING ||
            clientState == tech.torlando.eridanus.rrc.ClientState.AWAITING_WELCOME

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

            if (connectionError != null) {
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
                        Text(
                            text = connectionError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.clearConnectionError() }) {
                            Text("Dismiss")
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
                val starred = discoveredHubs.filter { it.starred }
                val unstarred = discoveredHubs.filter { !it.starred }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (starred.isNotEmpty()) {
                        item {
                            Text(
                                text = "Favorites",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp,
                                ),
                            )
                        }
                        items(starred, key = { "starred_${it.hexHash}" }) { hub ->
                            HubCard(
                                hub = hub,
                                isStarred = true,
                                onToggleStar = { viewModel.toggleHubStar(hub.hexHash) },
                                onConnect = { viewModel.connectToHub(hub.hash) },
                                onRemove = { viewModel.removeHub(hub.hexHash) },
                                connectEnabled = !isConnected && !isConnecting,
                                connectLabel = if (isConnecting) "Connecting..." else "Connect",
                            )
                        }
                    }
                    if (unstarred.isNotEmpty()) {
                        item {
                            Text(
                                text = if (starred.isNotEmpty()) "Discovered" else "Discovered Hubs",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 16.dp, end = 16.dp,
                                    top = if (starred.isNotEmpty()) 16.dp else 8.dp,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                        items(unstarred, key = { "unstarred_${it.hexHash}" }) { hub ->
                            HubCard(
                                hub = hub,
                                isStarred = false,
                                onToggleStar = { viewModel.toggleHubStar(hub.hexHash) },
                                onConnect = { viewModel.connectToHub(hub.hash) },
                                onRemove = { viewModel.removeHub(hub.hexHash) },
                                connectEnabled = !isConnected && !isConnecting,
                                connectLabel = if (isConnecting) "Connecting..." else "Connect",
                            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubCard(
    hub: tech.torlando.eridanus.viewmodel.DiscoveredHub,
    isStarred: Boolean,
    onToggleStar: () -> Unit,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
    connectEnabled: Boolean,
    connectLabel: String,
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    },
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleStar) {
                    Icon(
                        imageVector = if (isStarred) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isStarred) "Unstar" else "Star",
                        tint = if (isStarred) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    Text(
                        text = "Last heard ${formatLastHeard(hub.lastSeen)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onConnect,
                    enabled = connectEnabled,
                ) {
                    Text(connectLabel)
                }
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Remove") },
                onClick = {
                    showMenu = false
                    onRemove()
                },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
            )
        }
    }
}

/** Human-readable "last heard" string from an announce timestamp (ms since epoch). */
private fun formatLastHeard(lastSeen: Long): String {
    val now = System.currentTimeMillis()
    return if (now - lastSeen < DateUtils.MINUTE_IN_MILLIS) {
        "just now"
    } else {
        DateUtils.getRelativeTimeSpanString(
            lastSeen,
            now,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
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
