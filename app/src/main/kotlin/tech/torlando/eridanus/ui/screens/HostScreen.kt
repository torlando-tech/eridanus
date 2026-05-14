// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.torlando.eridanus.data.DefaultRoomConfig
import tech.torlando.eridanus.viewmodel.EridanusViewModel

private data class ModeOption(val flag: String, val label: String, val description: String)

private val MODE_OPTIONS = listOf(
    ModeOption("r", "Registered (+r)", "Persists when empty, appears in /list"),
    ModeOption("n", "No Outside Msgs (+n)", "Only members can send messages"),
    ModeOption("t", "Ops-Only Topic (+t)", "Only operators can change the topic"),
    ModeOption("m", "Moderated (+m)", "Only voiced users can speak"),
    ModeOption("i", "Invite Only (+i)", "Users need an invite to join"),
    ModeOption("p", "Private (+p)", "Hidden from the public room list"),
    ModeOption("k", "Key Protected (+k)", "Requires a password to join"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HostScreen(viewModel: EridanusViewModel, onNavigateToRooms: () -> Unit = {}) {
    val hubRunning by viewModel.hubRunning.collectAsState()
    val hubClients by viewModel.hubClients.collectAsState()
    val hubName by viewModel.hubName.collectAsState()
    val reticulumStarted by viewModel.reticulumStarted.collectAsState()
    val hubDestHash by viewModel.hubDestHash.collectAsState()
    val announceInterval by viewModel.announceInterval.collectAsState()
    val clientState by viewModel.clientState.collectAsState()
    val hubGreeting by viewModel.hubGreeting.collectAsState()
    val defaultRooms by viewModel.hubDefaultRooms.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Navigate to rooms when connection to own hub becomes active
    var connectingToOwnHub by remember { mutableStateOf(false) }
    LaunchedEffect(clientState) {
        if (connectingToOwnHub && clientState == tech.torlando.eridanus.rrc.ClientState.ACTIVE) {
            connectingToOwnHub = false
            onNavigateToRooms()
        } else if (clientState == tech.torlando.eridanus.rrc.ClientState.DISCONNECTED) {
            connectingToOwnHub = false
        }
    }

    var localHubName by remember { mutableStateOf(hubName) }
    var localGreeting by remember { mutableStateOf(hubGreeting) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(title = { Text("Host Hub") })
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Hub name + on/off
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = localHubName,
                        onValueChange = { localHubName = it; viewModel.setHubName(it) },
                        label = { Text("Hub Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !hubRunning,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Hub Active",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (hubRunning) "Running" else "Stopped",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hubRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = hubRunning,
                            onCheckedChange = { checked ->
                                if (checked) viewModel.startHub() else viewModel.stopHub()
                            },
                            enabled = reticulumStarted,
                        )
                    }
                }
            }

            // Hub status (when running)
            if (hubRunning) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Hub Status",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Connected Clients")
                            Text(
                                text = "$hubClients",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        hubDestHash?.let { hash ->
                            val hex = hash.joinToString("") { "%02x".format(it) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(hex))
                                        Toast.makeText(context, "Hash copied", Toast.LENGTH_SHORT).show()
                                    },
                            ) {
                                Text(
                                    text = "Hash (tap to copy)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = hex,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    connectingToOwnHub = true
                                    viewModel.connectToOwnHub()
                                },
                                enabled = clientState == tech.torlando.eridanus.rrc.ClientState.DISCONNECTED,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Connect")
                            }
                            OutlinedButton(
                                onClick = { viewModel.announceHub() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Announce")
                            }
                            OutlinedButton(
                                onClick = {
                                    val hexHash = hubDestHash?.joinToString("") { "%02x".format(it) } ?: return@OutlinedButton
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "Connect to my Eridanus hub: $hexHash")
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Hub Hash"))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Share")
                            }
                        }
                    }
                }
            }

            // Hub greeting
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Hub Settings",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = localGreeting,
                        onValueChange = { localGreeting = it; viewModel.setHubGreeting(it) },
                        label = { Text("Welcome Message") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        supportingText = { Text("Shown to clients when they connect") },
                        enabled = !hubRunning,
                    )
                }
            }

            // Default rooms
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Default Rooms",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (!hubRunning) {
                            TextButton(onClick = {
                                viewModel.setHubDefaultRooms(listOf(DefaultRoomConfig(name = "")) + defaultRooms)
                                coroutineScope.launch {
                                    // Scroll so the new card (just below this one) is visible
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Text("Add Room")
                            }
                        }
                    }
                    Text(
                        text = "These rooms are pre-created when the hub starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            defaultRooms.forEachIndexed { index, roomCfg ->
                DefaultRoomCard(
                    config = roomCfg,
                    enabled = !hubRunning,
                    onUpdate = { updated ->
                        viewModel.setHubDefaultRooms(
                            defaultRooms.toMutableList().also { it[index] = updated }
                        )
                    },
                    onDelete = {
                        viewModel.setHubDefaultRooms(
                            defaultRooms.toMutableList().also { it.removeAt(index) }
                        )
                    },
                )
            }

            // Announce interval selector
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Announce Interval",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val options = listOf(
                        0 to "Off",
                        30 to "30s",
                        60 to "1m",
                        300 to "5m",
                        600 to "10m",
                        3600 to "1h",
                        10800 to "3h",
                        21600 to "6h",
                        43200 to "12h",
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for ((seconds, label) in options) {
                            FilterChip(
                                selected = announceInterval == seconds,
                                onClick = { viewModel.setAnnounceInterval(seconds) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            if (!reticulumStarted) {
                Text(
                    text = "Waiting for Reticulum to start...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DefaultRoomCard(
    config: DefaultRoomConfig,
    enabled: Boolean,
    onUpdate: (DefaultRoomConfig) -> Unit,
    onDelete: () -> Unit,
) {
    var localName by remember(config) { mutableStateOf(config.name) }
    var localTopic by remember(config) { mutableStateOf(config.topic) }
    var localKey by remember(config) { mutableStateOf(config.key) }
    var expanded by remember { mutableStateOf(config.name.isBlank()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (localName.isNotBlank()) "#${localName.trim().lowercase()}" else "New Room",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!expanded && config.modes.isNotBlank()) {
                        Text(
                            text = config.modes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (enabled) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove room",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = localName,
                        onValueChange = {
                            localName = it
                            onUpdate(config.copy(name = it.trim().lowercase()))
                        },
                        label = { Text("Room Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                    )

                    OutlinedTextField(
                        value = localTopic,
                        onValueChange = {
                            localTopic = it
                            onUpdate(config.copy(topic = it))
                        },
                        label = { Text("Topic") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                    )

                    Text(
                        text = "Modes",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (mode in MODE_OPTIONS) {
                            val isSet = config.modes.contains(mode.flag)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = isSet,
                                    onClick = {
                                        if (enabled) {
                                            val newModes = if (isSet) {
                                                config.modes.replace(mode.flag, "")
                                            } else {
                                                config.modes + mode.flag
                                            }
                                            val flags = newModes.filter { it.isLetter() }
                                            val sorted = flags.toSortedSet().joinToString("")
                                            onUpdate(config.copy(modes = if (sorted.isNotEmpty()) "+$sorted" else ""))
                                        }
                                    },
                                    label = { Text(mode.label) },
                                    enabled = enabled,
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    if (config.modes.contains("k")) {
                        OutlinedTextField(
                            value = localKey,
                            onValueChange = {
                                localKey = it
                                onUpdate(config.copy(key = it))
                            },
                            label = { Text("Room Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("Password required to join") },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}
