// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import tech.torlando.eridanus.data.DarkModeOption
import tech.torlando.eridanus.ui.components.BatteryOptimizationCard
import tech.torlando.eridanus.ui.components.IdentityCard
import tech.torlando.eridanus.ui.components.SharedInstanceBannerCard
import tech.torlando.eridanus.ui.theme.PresetTheme
import tech.torlando.eridanus.viewmodel.EridanusViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: EridanusViewModel) {
    val currentTheme by viewModel.theme.collectAsState()
    val darkMode by viewModel.darkMode.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val reticulumStarted by viewModel.reticulumStarted.collectAsState()
    val connectedToShared by viewModel.connectedToSharedInstance.collectAsState()
    val wasConnectedToShared by viewModel.wasConnectedToSharedInstance.collectAsState()
    val isRestarting by viewModel.isRestarting.collectAsState()
    val clientState by viewModel.clientState.collectAsState()
    val keepConnectionAlive by viewModel.keepConnectionAlive.collectAsState()
    var localNickname by remember { mutableStateOf(nickname) }
    var batteryCardExpanded by remember { mutableStateOf(false) }
    var identityCardExpanded by remember { mutableStateOf(false) }
    val isDark = when (darkMode) {
        DarkModeOption.SYSTEM -> isSystemInDarkTheme()
        DarkModeOption.LIGHT -> false
        DarkModeOption.DARK -> true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Nickname
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Nickname",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = localNickname,
                        onValueChange = { localNickname = it; viewModel.setNickname(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        placeholder = { Text("Enter your nickname") },
                    )
                }
            }

            // Shared-instance banner (3-state, see SharedInstanceBannerCard
            // — adapted from v0.10.x columba's pattern). Owns the "what is
            // Reticulum doing right now" surface; the watchdog in
            // EridanusViewModel drives the state flags.
            SharedInstanceBannerCard(
                connected = connectedToShared,
                wasConnected = wasConnectedToShared,
                isRestarting = isRestarting,
                backendIdentifier = viewModel.backendIdentifier,
                onRetry = { viewModel.retrySharedInstance() },
            )

            // Hub client status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Hub",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Connection")
                        Text(
                            text = clientState.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (clientState == tech.torlando.eridanus.rrc.ClientState.ACTIVE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (!reticulumStarted) {
                        Text(
                            text = "Waiting for Reticulum to finish starting…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Battery & background reliability (ported from columba's
            // BatteryOptimizationCard). A non-exempt app gets Doze-throttled
            // while backgrounded, which lets the shared-instance connection
            // go stale and silently stops announce/packet delivery.
            BatteryOptimizationCard(
                isExpanded = batteryCardExpanded,
                onExpandedChange = { batteryCardExpanded = it },
            )

            // Keep connection alive in background. Holds a partial wake lock
            // while connected to a hub so the CPU keeps running the RNS
            // threads through Doze — otherwise an idle device suspends and
            // the hub link silently drops. Off by default (continuous battery
            // cost); works best with the battery exemption above granted.
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setKeepConnectionAlive(!keepConnectionAlive) }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep connection alive in background",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Stay in your room while the screen is off. " +
                                "Uses more battery; grant the battery exemption above for it to work reliably.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = keepConnectionAlive,
                        onCheckedChange = { viewModel.setKeepConnectionAlive(it) },
                    )
                }
            }

            // Dark mode
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Dark Mode",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DarkModeOption.entries.forEach { option ->
                            val isSelected = option == darkMode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { viewModel.setDarkMode(option) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = option.displayName,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Theme picker
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    PresetTheme.entries.forEach { preset ->
                        val isSelected = preset == currentTheme
                        val colors = preset.getPreviewColors(isDark)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { viewModel.setTheme(preset) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Color preview dots
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(colors.first, colors.second, colors.third).forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .then(
                                                if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                else Modifier
                                            ),
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = preset.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Identity import/export (Sideband-compatible Base32 + raw 64-byte
            // file). Placed at the bottom because importing is destructive
            // and shouldn't sit in the user's primary attention path.
            IdentityCard(
                isExpanded = identityCardExpanded,
                onExpandedChange = { identityCardExpanded = it },
                viewModel = viewModel,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
