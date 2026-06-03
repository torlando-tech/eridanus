// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tech.torlando.eridanus.ui.theme.usernameColor
import tech.torlando.eridanus.viewmodel.EridanusViewModel
import tech.torlando.eridanus.viewmodel.ChatMessage
import tech.torlando.eridanus.viewmodel.RoomMember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
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
    CommandSuggestion("/me", "/me <action>", "Send an action/emote"),
    CommandSuggestion("/kick", "/kick <user>", "Kick a user"),
    CommandSuggestion("/ban", "/ban add|del|list [user]", "Manage bans"),
    CommandSuggestion("/op", "/op <user>", "Grant operator status"),
    CommandSuggestion("/deop", "/deop <user>", "Remove operator status"),
    CommandSuggestion("/voice", "/voice <user>", "Grant voice"),
    CommandSuggestion("/devoice", "/devoice <user>", "Remove voice"),
    CommandSuggestion("/mode", "/mode [+/-flags] [user]", "View or set room modes"),
    CommandSuggestion("/register", "/register", "Register the room"),
    CommandSuggestion("/unregister", "/unregister", "Unregister the room"),
    CommandSuggestion("/invite", "/invite add|del|list [user]", "Manage invites"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: EridanusViewModel,
    onNavigateBack: () -> Unit,
) {
    val currentRoom by viewModel.currentRoom.collectAsState()
    val allMessages by viewModel.messages.collectAsState()
    val roomMessages = allMessages[currentRoom] ?: emptyList()
    val roomTopics by viewModel.roomTopics.collectAsState()
    val roomMemberCounts by viewModel.roomMemberCounts.collectAsState()
    val roomMemberList by viewModel.roomMemberList.collectAsState()
    val selfNick by viewModel.nickname.collectAsState()
    val hideJoinPart by viewModel.hideJoinPart.collectAsState()
    // When join/part are hidden, drop them from the scrollback; new ones are
    // surfaced ephemerally by EphemeralJoinPartNotice instead.
    val visibleMessages = remember(roomMessages, hideJoinPart) {
        if (hideJoinPart) roomMessages.filterNot { it.isJoinPart } else roomMessages
    }
    val topic = currentRoom?.let { roomTopics[it] }
    val memberCount = currentRoom?.let { roomMemberCounts[it] }
    val members = currentRoom?.let { roomMemberList[it] } ?: emptyList()
    var inputField by remember { mutableStateOf(TextFieldValue()) }
    val listState = rememberLazyListState()
    var showMemberSheet by remember { mutableStateOf(false) }

    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(visibleMessages.size - 1)
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
                            text = { Text("Hide join/part") },
                            onClick = {
                                menuExpanded = false
                                viewModel.setHideJoinPart(!hideJoinPart)
                            },
                            trailingIcon = if (hideJoinPart) {
                                { Icon(Icons.Default.Check, contentDescription = "Enabled") }
                            } else {
                                null
                            },
                        )
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visibleMessages) { message ->
                        MessageItem(message, members, selfNick)
                    }
                }

                // While join/part are hidden, flash each new one here and let it
                // fade out, instead of keeping it in the scrollback above.
                EphemeralJoinPartNotice(
                    notices = viewModel.joinPartNotices,
                    room = currentRoom,
                    active = hideJoinPart,
                    members = members,
                    selfNick = selfNick,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            val inputText = inputField.text
            val filteredCommands = if (inputText.startsWith("/") && !inputText.contains(" ")) {
                COMMANDS.filter { it.command.startsWith(inputText.lowercase()) }
            } else emptyList()

            // User arg autocomplete for commands like /kick, /op, /ban add, etc.
            val userArgCommands = setOf("/kick", "/op", "/deop", "/voice", "/devoice")
            val userArgAfterSub = setOf("/ban", "/invite")
            val inputParts = inputText.split(" ")
            val inputCmd = inputParts.getOrNull(0)?.lowercase() ?: ""
            val (filteredMembers, memberPrefix) = when {
                inputCmd in userArgCommands && inputParts.size == 2 ->
                    members.matchingMention(inputParts[1]) to "${inputParts[0]} "
                inputCmd in userArgAfterSub && inputParts.size == 3
                        && inputParts[1].lowercase() in setOf("add", "del") ->
                    members.matchingMention(inputParts[2]) to "${inputParts[0]} ${inputParts[1]} "
                else -> emptyList<RoomMember>() to ""
            }

            // @-mention autocomplete: anywhere in a non-command message, the
            // word being typed under the cursor. Suppressed for commands, which
            // keep their own arg completers above.
            val cursor = inputField.selection
            val mentionQuery = if (!inputText.startsWith("/") && cursor.collapsed) {
                mentionQueryAt(inputText, cursor.start)
            } else null
            val filteredMentions = mentionQuery
                ?.let { members.matchingMention(it.partial) }
                ?: emptyList()

            val showSuggestions = filteredCommands.isNotEmpty() ||
                filteredMembers.isNotEmpty() || filteredMentions.isNotEmpty()
            AnimatedVisibility(visible = showSuggestions) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
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
                        // Command-arg completion (/kick, /ban add, …): replace the
                        // whole input with "<command prefix><name> ".
                        filteredMembers.forEach { member ->
                            MemberSuggestionRow(member) {
                                val name = member.nick ?: member.hashPrefix
                                val text = "$memberPrefix$name "
                                inputField = TextFieldValue(text, TextRange(text.length))
                            }
                        }
                        // @-mention completion: splice "@<name> " over just the
                        // @-token under the cursor, keeping the rest of the draft.
                        filteredMentions.forEach { member ->
                            MemberSuggestionRow(member) {
                                val q = mentionQuery ?: return@MemberSuggestionRow
                                val name = member.nick ?: member.hashPrefix
                                val result = applyMention(inputText, q, cursor.start, name)
                                inputField = TextFieldValue(result.text, TextRange(result.cursor))
                            }
                        }
                    }
                }
            }

            val sendCurrent = {
                if (inputText.isNotBlank()) {
                    viewModel.sendInput(inputText.trim())
                    inputField = TextFieldValue()
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
                    modifier = Modifier
                        .weight(1f)
                        // Bare Enter sends; Shift+Enter falls through to insert
                        // a newline. Hardware/desktop keyboards (e.g. Waydroid)
                        // emit a real Enter key here; soft keyboards usually
                        // surface a newline button instead, which still works.
                        // Require a *bare* Enter — Ctrl/Alt/Meta+Enter are
                        // common OS/WM shortcuts on desktop Linux/Waydroid, so
                        // leave those un-consumed rather than stealing them.
                        // Consume only the bare-Enter KeyDown so the newline
                        // isn't also inserted, and so the matching KeyUp
                        // doesn't fire a second send.
                        .onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            val isBareEnter = isEnter && !event.isShiftPressed &&
                                !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                            if (isBareEnter) {
                                if (event.type == KeyEventType.KeyDown) sendCurrent()
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text("Message #${currentRoom ?: ""}") },
                    // Multi-line so Shift+Enter can insert a newline; capped so
                    // a long draft doesn't crowd out the message list.
                    maxLines = 6,
                )
                IconButton(
                    onClick = sendCurrent,
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

/**
 * A tappable autocomplete suggestion for [member]: their nick (colored with the
 * same per-user color as their chat messages, muted when nickless) alongside
 * their hash prefix. Shared by the command-arg and `@`-mention completers.
 */
@Composable
private fun MemberSuggestionRow(member: RoomMember, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            member.nick ?: "(no nick)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (member.nick != null) usernameColor(member.hashPrefix)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            member.hashPrefix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            // Same per-user color as their chat messages (keyed on hashPrefix); the
            // nickless placeholder stays muted since there's no username to color.
            color = if (member.nick != null) usernameColor(member.hashPrefix)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = member.hashPrefix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * First 6 bytes of a destination hash as lowercase hex (12 chars) — the same
 * identity key [RoomMember.hashPrefix] uses, so a sender's message color matches
 * their member-list color.
 */
private fun ByteArray.toColorKey(): String =
    take(6).joinToString("") { "%02x".format(it) }

// Ephemeral join/part flash timing: hold at full opacity briefly, then fade to
// nothing over ~2s ("a couple seconds").
private const val EPHEMERAL_HOLD_MS = 600L
private const val EPHEMERAL_FADE_MS = 2000L

/**
 * A single join/part notice that flashes in and fades to nothing over a couple
 * seconds — shown only while [active] (the "hide join/part" setting is on). Each
 * new notice for [room] restarts the show-then-fade cycle (latest wins), and
 * the flash is cancelled when the room changes or [active] goes false. Rendered
 * via [MessageItem] so it reuses the same nick resolution and styling as the
 * persistent notice it stands in for. Occupies no layout space when idle, so it
 * never shifts the composer.
 */
@Composable
private fun EphemeralJoinPartNotice(
    notices: SharedFlow<Pair<String, ChatMessage>>,
    room: String?,
    active: Boolean,
    members: List<RoomMember>,
    selfNick: String,
    modifier: Modifier = Modifier,
) {
    var notice by remember { mutableStateOf<ChatMessage?>(null) }
    var trigger by remember { mutableStateOf(0) }
    val alpha = remember { Animatable(0f) }

    // The collector lives across recompositions; read the latest values through
    // these so it doesn't capture a stale room/active.
    val activeState = rememberUpdatedState(active)
    val roomState = rememberUpdatedState(room)
    LaunchedEffect(Unit) {
        notices.collect { (noticeRoom, message) ->
            if (activeState.value && noticeRoom == roomState.value) {
                notice = message
                trigger++
            }
        }
    }

    // Cancel any in-flight flash when leaving the room or turning the toggle off.
    LaunchedEffect(room, active) {
        notice = null
        alpha.snapTo(0f)
    }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        delay(EPHEMERAL_HOLD_MS)
        alpha.animateTo(0f, animationSpec = tween(EPHEMERAL_FADE_MS.toInt()))
        notice = null
    }

    notice?.let { current ->
        // The surface background keeps the line legible while it overlays the
        // bottom of the scrollback, and fades out together with the text.
        Box(
            modifier = modifier
                .graphicsLayer { this.alpha = alpha.value }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            MessageItem(current, members, selfNick)
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessage, members: List<RoomMember>, selfNick: String) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val time = timeFormat.format(Date(message.timestamp))

    when {
        message.isNotice -> {
            // Structured join notices (memberHash present, body = "joined")
            // resolve the actor here from the *current* member list so the
            // notice gains the joiner's nick the moment we learn it via
            // /who or their first message. Part notices and free-text
            // notices set memberHash = null and bake the full text into
            // body upstream.
            val body = if (message.memberHash != null) {
                val prefix = message.memberHash.joinToString("") { "%02x".format(it) }.take(12)
                val nick = members.firstOrNull { it.hashPrefix == prefix }?.nick
                val actor = nick
                    ?: message.memberHash.take(6).joinToString("") { "%02x".format(it) }
                "$actor ${message.body}"
            } else {
                message.body
            }
            Text(
                text = "-- $time $body",
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
        message.isAction -> {
            // IRC-style emote: "* nick does something". The actor is colored
            // with the same per-user color used for normal messages so the
            // speaker stays recognizable; the action text is italicized.
            val actor = message.nick ?: message.src?.toColorKey() ?: "???"
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
                    text = "* $actor ${message.body}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = usernameColor(message.src?.toColorKey() ?: message.nick.orEmpty()),
                )
            }
        }
        else -> {
            // Highlight @-mentions of the local user's own nick: the token gets
            // a tinted chip and the whole row a subtle tint so messages aimed at
            // you are easy to spot when scanning the timeline.
            val mentionRanges = remember(message.body, selfNick) {
                selfMentionRanges(message.body, selfNick)
            }
            val rowModifier = if (mentionRanges.isNotEmpty()) {
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            } else {
                Modifier.padding(vertical = 2.dp)
            }
            Row(
                modifier = rowModifier,
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
                    // Key the color on the sender's stable destination-hash prefix
                    // (same identity RoomMember uses), falling back to the nick when
                    // unknown, so each speaker is consistently distinguishable.
                    color = usernameColor(message.src?.toColorKey() ?: message.nick.orEmpty()),
                )
                LinkifiedText(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    mentionRanges = mentionRanges,
                )
            }
        }
    }
}

/**
 * Message body text that (a) is selectable so the user can copy it (handy
 * for grabbing a pasted URL), (b) renders any http(s) links as tappable,
 * underlined primary-colored spans that open in the system browser, (c) renders
 * bare NomadNet page addresses (`<hash>:/path.mu`) as tappable spans that hand
 * off to an installed NomadNet app, and (d) renders self-mentions
 * ([mentionRanges]) as a tinted chip.
 *
 * Tappable links and text selection coexisting in one [Text] is a Compose
 * 1.7 capability ([LinkAnnotation] inside a [SelectionContainer]); on 1.6
 * the two gesture handlers fought each other.
 */
@Composable
private fun LinkifiedText(
    text: String,
    style: TextStyle,
    mentionRanges: List<IntRange> = emptyList(),
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val mentionBg = MaterialTheme.colorScheme.tertiaryContainer
    val mentionFg = MaterialTheme.colorScheme.onTertiaryContainer
    val context = LocalContext.current
    // A tap on a NomadNet link fires an ACTION_VIEW intent for its
    // nomadnetwork:// URI, which an installed NomadNet-capable app (Columba,
    // Sideband, …) handles; with none installed we toast instead of crashing.
    // Web links need no listener — LinkAnnotation.Url routes through the
    // LocalUriHandler to the platform browser.
    val nomadnetListener = remember(context) {
        LinkInteractionListener { link ->
            (link as? LinkAnnotation.Clickable)?.tag?.let { openNomadNetLink(context, it) }
        }
    }
    val annotated = remember(text, linkColor, mentionRanges, mentionBg, mentionFg, nomadnetListener) {
        val mentionStyle = SpanStyle(
            background = mentionBg,
            color = mentionFg,
            fontWeight = FontWeight.Medium,
        )
        buildMessageBody(text, linkColor, mentionRanges, mentionStyle, nomadnetListener)
    }
    SelectionContainer {
        Text(text = annotated, style = style)
    }
}

/**
 * Open a NomadNet [uri] (a `nomadnetwork://…` address) by handing it to whatever
 * app on the device registers that scheme. Eridanus has no in-app NomadNet
 * browser, so with no handler installed we show a toast rather than crash.
 */
private fun openNomadNetLink(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "No NomadNet app installed to open this link",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * Build an [AnnotatedString] for a message body, interleaving link and mention
 * runs in a single left-to-right pass:
 * - each http(s) URL becomes a tappable [LinkAnnotation.Url] (underlined,
 *   [linkColor]) that opens in the system browser; trailing punctuation grabbed
 *   by the match stays plain text;
 * - each bare NomadNet address ([detectChatLinks]) becomes a tappable
 *   [LinkAnnotation.Clickable] carrying its `nomadnetwork://` URI, routed on tap
 *   to [nomadnetListener];
 * - each range in [mentionRanges] is styled with [mentionStyle].
 *
 * A mention overlapping a link is dropped (the link wins, since it's tappable);
 * web/NomadNet overlaps are already resolved by [detectChatLinks]. Returns a
 * plain string when there's nothing to style.
 */
private fun buildMessageBody(
    text: String,
    linkColor: Color,
    mentionRanges: List<IntRange>,
    mentionStyle: SpanStyle,
    nomadnetListener: LinkInteractionListener,
): AnnotatedString {
    val links = detectChatLinks(text)
    val mentions = mentionRanges.filter { mr ->
        links.none { it.range.first <= mr.last && mr.first <= it.range.last }
    }
    if (links.isEmpty() && mentions.isEmpty()) return AnnotatedString(text)

    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )
    // Links don't overlap (resolved in detectChatLinks) and mentions don't
    // overlap each other; mentions overlapping a link were filtered out above,
    // so every special run has a distinct start index.
    val linkByStart = links.associateBy { it.range.first }
    val mentionByStart = mentions.associateBy { it.first }
    val starts = (linkByStart.keys + mentionByStart.keys).sorted()

    return buildAnnotatedString {
        var cursor = 0
        for (start in starts) {
            if (start > cursor) append(text.substring(cursor, start))
            val link = linkByStart[start]
            when (link?.kind) {
                ChatLinkKind.WEB -> {
                    val (linkUrl, trailing) = splitWebUrlTrailing(link.text)
                    withLink(LinkAnnotation.Url(url = linkUrl, styles = linkStyles)) {
                        append(linkUrl)
                    }
                    // Trailing punctuation the match grabbed stays plain text.
                    if (trailing.isNotEmpty()) append(trailing)
                    cursor = link.range.last + 1
                }
                ChatLinkKind.NOMADNET -> {
                    // The matched address carries no trailing punctuation to
                    // trim (the regex excludes it). Tap → nomadnetListener.
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = toNomadNetUri(link.text),
                            styles = linkStyles,
                            linkInteractionListener = nomadnetListener,
                        ),
                    ) {
                        append(link.text)
                    }
                    cursor = link.range.last + 1
                }
                null -> {
                    val mr = mentionByStart.getValue(start)
                    withStyle(mentionStyle) { append(text.substring(mr.first, mr.last + 1)) }
                    cursor = mr.last + 1
                }
            }
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
