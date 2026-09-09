// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import android.text.format.DateUtils
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import tech.torlando.eridanus.viewmodel.EridanusViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubBrowserScreen(
    viewModel: EridanusViewModel,
    onNavigateToRooms: () -> Unit,
) {
    val discoveredHubs by viewModel.discoveredHubs.collectAsState()
    val clientState by viewModel.clientState.collectAsState()
    val connectedHub by viewModel.connectedHubName.collectAsState()
    val reticulumStarted by viewModel.reticulumStarted.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val joinedRooms by viewModel.joinedRooms.collectAsState()
    val availableRooms by viewModel.availableRooms.collectAsState()
    var showManualDialog by remember { mutableStateOf(false) }
    var manualHash by remember { mutableStateOf("") }
    var manualFavorite by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var hubQuery by remember { mutableStateOf("") }

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
                // Adaptive status line: lead with joined-room count once the
                // user is in rooms, otherwise nudge toward the ones available
                // to join; fall back to a bare "Connected" before the roster
                // arrives.
                val roomStatus = when {
                    joinedRooms.isNotEmpty() ->
                        "Connected · ${joinedRooms.size} ${if (joinedRooms.size == 1) "room" else "rooms"} joined"
                    availableRooms.isNotEmpty() ->
                        "Connected · ${availableRooms.size} ${if (availableRooms.size == 1) "room" else "rooms"} available"
                    else -> "Connected"
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column {
                            Text(
                                text = connectedHub ?: "Connected Hub",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = roomStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Per Material's action-placement convention, the
                            // primary action (View Rooms) sits on the trailing
                            // edge; the secondary/dismissive Disconnect leads.
                            OutlinedButton(
                                onClick = { viewModel.disconnectFromHub() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Disconnect")
                            }
                            Button(
                                onClick = onNavigateToRooms,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("View Rooms")
                            }
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
                val filteredHubs = remember(discoveredHubs, hubQuery) {
                    filterHubs(discoveredHubs, hubQuery)
                }
                val starred = filteredHubs.filter { it.starred }
                val unstarred = filteredHubs.filter { !it.starred }

                OutlinedTextField(
                    value = hubQuery,
                    onValueChange = { hubQuery = it },
                    label = { Text("Search hubs") },
                    placeholder = { Text("Name or hash") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (hubQuery.isNotEmpty()) {
                            IconButton(onClick = { hubQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (filteredHubs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No hubs match “${hubQuery.trim()}”.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
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
        }

        if (showManualDialog) {
            AlertDialog(
                onDismissRequest = {
                    showManualDialog = false
                    manualHash = ""
                    manualFavorite = false
                    manualError = null
                },
                title = { Text("Connect to Hub") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = manualHash,
                            onValueChange = { manualHash = it },
                            label = { Text("Hub destination hash (hex)") },
                            singleLine = true,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = manualFavorite,
                                onCheckedChange = { manualFavorite = it },
                            )
                            Text(
                                text = "Save as favorite",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        manualError?.let { err ->
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val hash = parseHexHash(manualHash.trim())
                            if (hash != null) {
                                // addHub also seeds the discovered-hub table
                                // (favorite flag honored) so the hub is
                                // remembered across restarts — issue #42.
                                viewModel.addHub(hash, manualFavorite)
                                showManualDialog = false
                                manualHash = ""
                                manualFavorite = false
                                manualError = null
                            } else {
                                manualError = "That doesn't look like a hub hash — " +
                                    "expected 32 hex characters (16 bytes)."
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
                        manualFavorite = false
                        manualError = null
                    }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

/**
 * Filters the discovered-hub list by the browser's search query: a hub
 * matches when its name OR its hex hash contains the query
 * (case-insensitive, surrounding whitespace ignored). hexHash is already
 * lower-case by construction, so the lowercasing only matters for name
 * matches and hash-prefix queries typed in uppercase. Empty/blank query
 * returns the list unchanged, so the Favorites/Discovered split the screen
 * does afterwards is untouched in the unfiltered case.
 *
 * Visible-for-testing: unit-tested in HubSearchFilterTest.
 */
internal fun filterHubs(
    hubs: List<tech.torlando.eridanus.viewmodel.DiscoveredHub>,
    query: String,
): List<tech.torlando.eridanus.viewmodel.DiscoveredHub> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return hubs
    return hubs.filter { it.name.lowercase().contains(q) || it.hexHash.contains(q) }
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
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
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
                text = { Text("Copy hash") },
                onClick = {
                    showMenu = false
                    clipboardManager.setText(AnnotatedString(hub.hexHash))
                    Toast.makeText(context, "Hash copied", Toast.LENGTH_SHORT).show()
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
            )
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

/**
 * Parses a user-entered hub destination hash (hex, optional spaces /
 * colon separators, case-insensitive) into 16 bytes. Returns null for
 * anything that is not exactly a 32-char hex string after cleaning —
 * RNS identity hashes are truncated to 128 bits
 * (RNS.Identity.TRUNCATED_HASHLENGTH), so a hub hash is 32 hex
 * characters. Rejecting other lengths early keeps the manual "Enter
 * Hash" dialog from connecting to a truncated (or full-256-bit) hash.
 *
 * Visible-for-testing: unit-tested in HubHashParseTest.
 */
internal fun parseHexHash(hex: String): ByteArray? {
    val cleaned = hex.replace(" ", "").replace(":", "").lowercase()
    if (cleaned.length != 32 || !cleaned.all { it in "0123456789abcdef" }) return null
    return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
