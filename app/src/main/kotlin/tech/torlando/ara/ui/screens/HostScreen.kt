package tech.torlando.ara.ui.screens

import android.widget.Toast
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.torlando.ara.viewmodel.AraViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HostScreen(viewModel: AraViewModel) {
    val hubRunning by viewModel.hubRunning.collectAsState()
    val hubClients by viewModel.hubClients.collectAsState()
    val hubName by viewModel.hubName.collectAsState()
    val reticulumStarted by viewModel.reticulumStarted.collectAsState()
    val hubDestHash by viewModel.hubDestHash.collectAsState()
    val announceInterval by viewModel.announceInterval.collectAsState()
    val clientState by viewModel.clientState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = hubName,
                        onValueChange = { viewModel.setHubName(it) },
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

            if (hubRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
                                onClick = { viewModel.connectToOwnHub() },
                                enabled = clientState == tech.torlando.ara.rrc.ClientState.DISCONNECTED,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Connect as Client")
                            }
                            OutlinedButton(
                                onClick = { viewModel.announceHub() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Announce Now")
                            }
                        }
                    }
                }
            }

            // Announce interval selector (visible regardless of hub state)
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
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
