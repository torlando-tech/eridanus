// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.torlando.eridanus.R
import kotlinx.coroutines.launch
import tech.torlando.eridanus.viewmodel.EridanusViewModel

private const val PAGE_COUNT = 4

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(viewModel: EridanusViewModel) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val coroutineScope = rememberCoroutineScope()

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> ConnectPage(viewModel)
                    2 -> NicknamePage(viewModel)
                    3 -> FindHubPage()
                }
            }

            // Dot indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(PAGE_COUNT) { index ->
                    val color by animateColorAsState(
                        targetValue = if (index == pagerState.currentPage)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.outlineVariant,
                        label = "dot",
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }) {
                        Text("Back")
                    }
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }

                if (pagerState.currentPage < PAGE_COUNT - 1) {
                    Button(onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }) {
                        Text("Next")
                    }
                } else {
                    Button(onClick = { viewModel.completeOnboarding() }) {
                        Text("Get Started")
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Eridanus",
            modifier = Modifier.size(160.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Welcome to Eridanus",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "IRC-style chatrooms over Reticulum — private, resilient, off-grid messaging with no servers, no accounts, and no internet required.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Eridanus uses the Reticulum Relay Chat protocol to let you host and join chat hubs that work over any Reticulum transport — LoRa, packet radio, local WiFi, or the internet.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectPage(viewModel: EridanusViewModel) {
    val reticulumStarted by viewModel.reticulumStarted.collectAsState()
    val connectedToSharedInstance by viewModel.connectedToSharedInstance.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Connect to Reticulum",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Eridanus needs a Reticulum shared instance to discover hubs and communicate with peers. Run Sideband, rnsd in Termux, or Reticulum-for-Android on this device to provide one.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))

        // Connection status
        val statusText = when {
            connectedToSharedInstance -> "Connected to shared instance"
            reticulumStarted -> "Reticulum started (standalone mode)"
            else -> "Starting Reticulum..."
        }
        val statusColor = when {
            connectedToSharedInstance -> MaterialTheme.colorScheme.primary
            reticulumStarted -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            color = statusColor,
            textAlign = TextAlign.Center,
        )

        if (reticulumStarted && !connectedToSharedInstance) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No shared instance found. A shared instance (Sideband, rnsd via Termux, or Reticulum-for-Android) must be running on this device for Eridanus to work.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = { viewModel.retrySharedInstance() }) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun NicknamePage(viewModel: EridanusViewModel) {
    val nickname by viewModel.nickname.collectAsState()
    var localNickname by remember { mutableStateOf(nickname) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Choose a Nickname",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "This is how you'll appear in chat.\nYou can change it later in Settings or with /nick.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = localNickname,
            onValueChange = {
                localNickname = it
                viewModel.setNickname(it)
            },
            label = { Text("Nickname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FindHubPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Find a Hub",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "There are a few ways to start chatting:",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BulletItem(
                title = "Discover",
                body = "Wait for hub announcements to appear on the Discover tab. Hubs on your Reticulum network will show up automatically.",
            )
            BulletItem(
                title = "Enter a Hash",
                body = "If someone shares their hub hash with you, paste it on the Discover tab to connect directly.",
            )
            BulletItem(
                title = "Host Your Own",
                body = "Create your own hub on the Host tab. Others on your network can discover and connect to it.",
            )
        }
    }
}

@Composable
private fun BulletItem(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
