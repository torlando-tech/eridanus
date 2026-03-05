package tech.torlando.ara.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.torlando.ara.viewmodel.AraViewModel
import tech.torlando.ara.viewmodel.ChatMessage
import tech.torlando.ara.viewmodel.RoomMember
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var inputText by remember { mutableStateOf("") }
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
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message #${currentRoom ?: ""}") },
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendInput(inputText.trim())
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
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
