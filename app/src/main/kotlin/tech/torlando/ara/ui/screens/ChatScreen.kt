package tech.torlando.ara.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import tech.torlando.ara.viewmodel.AraViewModel
import tech.torlando.ara.viewmodel.ChatMessage
import tech.torlando.ara.viewmodel.RoomMember
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CommandSuggestion(val command: String, val syntax: String, val description: String)

private val COMMANDS = listOf(
    CommandSuggestion("/help", "/help", "Show available commands"),
    CommandSuggestion("/list", "/list", "List public rooms"),
    CommandSuggestion("/topic", "/topic [text]", "View or set room topic"),
    CommandSuggestion("/who", "/who", "List room members"),
    CommandSuggestion("/nick", "/nick <name>", "Change your nickname"),
    CommandSuggestion("/kick", "/kick <user>", "Kick a user"),
    CommandSuggestion("/ban", "/ban add|del|list [user]", "Manage bans"),
    CommandSuggestion("/op", "/op <user>", "Grant operator status"),
    CommandSuggestion("/deop", "/deop <user>", "Remove operator status"),
    CommandSuggestion("/voice", "/voice <user>", "Grant voice"),
    CommandSuggestion("/devoice", "/devoice <user>", "Remove voice"),
    CommandSuggestion("/mode", "/mode [+/-flags]", "View or set room modes"),
    CommandSuggestion("/register", "/register", "Register the room"),
    CommandSuggestion("/unregister", "/unregister", "Unregister the room"),
    CommandSuggestion("/invite", "/invite add|del|list [user]", "Manage invites"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: AraViewModel,
    onNavigateBack: () -> Unit,
) {
    val currentRoom by viewModel.currentRoom.collectAsState()
    val allMessages by viewModel.messages.collectAsState()
    val roomMessages = allMessages[currentRoom] ?: emptyList()
    val roomTopics by viewModel.roomTopics.collectAsState()
    val roomMemberCounts by viewModel.roomMemberCounts.collectAsState()
    val roomMemberList by viewModel.roomMemberList.collectAsState()
    val topic = currentRoom?.let { roomTopics[it] }
    val memberCount = currentRoom?.let { roomMemberCounts[it] }
    val members = currentRoom?.let { roomMemberList[it] } ?: emptyList()
    var inputField by remember { mutableStateOf(TextFieldValue()) }
    val listState = rememberLazyListState()
    var showMemberSheet by remember { mutableStateOf(false) }

    LaunchedEffect(roomMessages.size) {
        if (roomMessages.isNotEmpty()) {
            listState.animateScrollToItem(roomMessages.size - 1)
        }
    }

    if (showMemberSheet) {
        MemberListSheet(
            members = members,
            memberCount = memberCount ?: 0,
            roomName = currentRoom ?: "",
            onDismiss = { showMemberSheet = false },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("#${currentRoom ?: ""}")
                        val subtitle = buildString {
                            if (topic != null) append(topic)
                            if (topic != null && memberCount != null) append(" \u00b7 ")
                            if (memberCount != null) append("$memberCount members")
                        }
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.clickable {
                                    currentRoom?.let { room ->
                                        viewModel.refreshMemberList(room)
                                        showMemberSheet = true
                                    }
                                },
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Leave Room") },
                            onClick = {
                                menuExpanded = false
                                currentRoom?.let { viewModel.partRoom(it); onNavigateBack() }
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val navBarBottom = WindowInsets.navigationBars.getBottom(density)
        val bottomInset = maxOf(imeBottom, navBarBottom)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(roomMessages) { message ->
                    MessageItem(message)
                }
            }

            val inputText = inputField.text
            val showSuggestions = inputText.startsWith("/") && !inputText.contains(" ")
            val filteredCommands = if (showSuggestions) {
                COMMANDS.filter { it.command.startsWith(inputText.lowercase()) }
            } else emptyList()

            AnimatedVisibility(visible = filteredCommands.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column {
                        filteredCommands.forEach { suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val text = suggestion.command + " "
                                        inputField = TextFieldValue(text, TextRange(text.length))
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    suggestion.syntax,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    suggestion.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputField,
                    onValueChange = { inputField = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message #${currentRoom ?: ""}") },
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendInput(inputText.trim())
                            inputField = TextFieldValue()
                        }
                    },
                    enabled = inputText.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }

            Spacer(modifier = Modifier.height(with(density) { bottomInset.toDp() }))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberListSheet(
    members: List<RoomMember>,
    memberCount: Int,
    roomName: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Members in #$roomName ($memberCount)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            HorizontalDivider()
            if (members.isEmpty()) {
                Text(
                    text = "No members",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn {
                    items(members) { member ->
                        MemberRow(member)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: RoomMember) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = member.nick ?: "(no nick)",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (member.nick != null) FontWeight.Medium else FontWeight.Normal,
            color = if (member.nick != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = member.hashPrefix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageItem(message: ChatMessage) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val time = timeFormat.format(Date(message.timestamp))

    when {
        message.isNotice -> {
            Text(
                text = "-- $time ${message.body}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        message.isError -> {
            Text(
                text = "-- $time ${message.body}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        else -> {
            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = message.nick ?: "???",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
