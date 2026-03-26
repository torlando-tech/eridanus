package tech.torlando.eridanus.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RoomListScreen(
    viewModel: EridanusViewModel,
    onNavigateToChat: (String) -> Unit,
) {
    val joinedRooms by viewModel.joinedRooms.collectAsState()
    val unreadCounts by viewModel.unreadCounts.collectAsState()
    val clientState by viewModel.clientState.collectAsState()
    val connectedHub by viewModel.connectedHubName.collectAsState()
    val availableRooms by viewModel.availableRooms.collectAsState()
    val greetingMessage by viewModel.hubGreetingMessage.collectAsState()
    var showJoinDialog by remember { mutableStateOf(false) }
    var roomNameInput by remember { mutableStateOf("") }
    var roomKeyInput by remember { mutableStateOf("") }

    val haptic = LocalHapticFeedback.current
    val filteredAvailable = availableRooms.filter { it.name !in joinedRooms }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Rooms")
                        if (connectedHub != null) {
                            Text(
                                text = "Connected to $connectedHub",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (clientState == tech.torlando.eridanus.rrc.ClientState.ACTIVE) {
                FloatingActionButton(onClick = { showJoinDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Join Room")
                }
            }
        },
    ) { paddingValues ->
        if (joinedRooms.isEmpty() && filteredAvailable.isEmpty() && greetingMessage == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (clientState == tech.torlando.eridanus.rrc.ClientState.ACTIVE) {
                        "No rooms joined yet.\nTap + to join a room."
                    } else {
                        "Not connected to a hub.\nGo to Discover to find one."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (greetingMessage != null) {
                    item(key = "greeting") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Text(
                                text = greetingMessage ?: "",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                if (joinedRooms.isNotEmpty()) {
                    item {
                        Text(
                            text = "Joined Rooms",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp,
                            ),
                        )
                    }
                    items(joinedRooms.toList().sorted()) { room ->
                        val unread = unreadCounts[room] ?: 0
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .combinedClickable(
                                        onClick = { onNavigateToChat(room) },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showMenu = true
                                        },
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "#$room",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (unread > 0) {
                                        Badge { Text("$unread") }
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Leave Room") },
                                    onClick = {
                                        showMenu = false
                                        viewModel.partRoom(room)
                                    },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                )
                            }
                        }
                    }
                }
                if (filteredAvailable.isNotEmpty()) {
                    item {
                        Text(
                            text = "Available Rooms",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp,
                                top = if (joinedRooms.isNotEmpty()) 16.dp else 8.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }
                    items(filteredAvailable) { room ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { viewModel.joinRoom(room.name) },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                Text(
                                    text = "#${room.name}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (room.topic != null) {
                                    Text(
                                        text = room.topic,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showJoinDialog) {
            AlertDialog(
                onDismissRequest = {
                    showJoinDialog = false
                    roomNameInput = ""
                    roomKeyInput = ""
                },
                title = { Text("Join Room") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = roomNameInput,
                            onValueChange = { roomNameInput = it },
                            label = { Text("Room name") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = roomKeyInput,
                            onValueChange = { roomKeyInput = it },
                            label = { Text("Key (optional)") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (roomNameInput.isNotBlank()) {
                                viewModel.joinRoom(
                                    roomNameInput.trim(),
                                    key = roomKeyInput.trim().ifEmpty { null },
                                )
                                showJoinDialog = false
                                roomNameInput = ""
                                roomKeyInput = ""
                            }
                        },
                    ) {
                        Text("Join")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showJoinDialog = false
                        roomNameInput = ""
                        roomKeyInput = ""
                    }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}
