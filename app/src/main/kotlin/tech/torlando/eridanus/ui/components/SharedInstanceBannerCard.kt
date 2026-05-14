package tech.torlando.eridanus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * State the card visually reflects. Exactly one mode is shown at a time;
 * the decision tree lives in [resolveMode] so the UI logic stays one place.
 *
 * - [Connected]      — a shared instance is attached. The trust-affirming
 *                       state: hub-side crypto / link state / packet framing
 *                       is happening through whichever host app the user
 *                       chose, not in-app.
 * - [Reconnecting]   — a teardown+restart cycle is in progress (either
 *                       user tapped Retry or the watchdog drove it after
 *                       detecting topology change).
 * - [LostHost]       — we were attached and the host went away. Sticky
 *                       informational state until the watchdog re-attaches
 *                       (or until [Waiting] takes over after a fresh app
 *                       restart).
 * - [Waiting]        — no host on 37428 and we never were attached. Shows
 *                       the actionable Retry button.
 */
private enum class CardMode { Connected, Reconnecting, LostHost, Waiting }

private fun resolveMode(
    connected: Boolean,
    wasConnected: Boolean,
    isRestarting: Boolean,
): CardMode = when {
    connected -> CardMode.Connected
    isRestarting -> CardMode.Reconnecting
    wasConnected -> CardMode.LostHost
    else -> CardMode.Waiting
}

/**
 * Settings-screen banner card surfacing the shared-Reticulum-instance state.
 *
 * Eridanus has no own-RNS fallback — all RNS traffic flows through a
 * shared-instance host (Sideband, Carina, rnsd in Termux, reticulum-android,
 * …). The host is detected generically via a loopback probe on
 * 127.0.0.1:37428 and the card can't distinguish which app is providing the
 * instance, so copy stays host-agnostic.
 *
 * Adapted from the v0.10.x columba `SharedInstanceBannerCard` (which served
 * a slightly different role — columba had own-RNS as a fallback, so its
 * card included a toggle. Eridanus's card is informational + a retry
 * button; the watchdog handles the recovery automatically in the
 * background).
 */
@Composable
fun SharedInstanceBannerCard(
    connected: Boolean,
    wasConnected: Boolean,
    isRestarting: Boolean,
    backendIdentifier: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = resolveMode(connected, wasConnected, isRestarting)

    var isExpanded by remember { mutableStateOf(false) }

    val containerColor: Color
    val contentColor: Color
    when (mode) {
        CardMode.Connected -> {
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
        CardMode.Reconnecting, CardMode.LostHost -> {
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        CardMode.Waiting -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }
    }

    val titleIcon: ImageVector = when (mode) {
        CardMode.Connected -> Icons.Filled.Link
        // Reconnecting state uses a CircularProgressIndicator instead of an
        // icon — Reconnecting never reads this value but a non-null default
        // keeps the when() exhaustive without an extra branch later.
        CardMode.Reconnecting -> Icons.Filled.Link
        CardMode.LostHost -> Icons.Filled.LinkOff
        CardMode.Waiting -> Icons.Filled.LinkOff
    }

    val title = when (mode) {
        CardMode.Connected -> "Connected to shared Reticulum instance"
        CardMode.Reconnecting -> "Reconnecting…"
        CardMode.LostHost -> "Shared Reticulum instance went offline"
        CardMode.Waiting -> "No shared Reticulum instance"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (mode == CardMode.Reconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                    } else {
                        Icon(
                            imageVector = titleIcon,
                            contentDescription = null,
                            tint = contentColor,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp
                                  else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = contentColor,
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (mode) {
                        CardMode.Connected -> ConnectedBody(backendIdentifier, contentColor)
                        CardMode.Reconnecting -> ReconnectingBody(contentColor)
                        CardMode.LostHost -> LostHostBody(contentColor)
                        CardMode.Waiting -> WaitingBody(contentColor, onRetry)
                    }
                }
            }

            // Always-visible retry affordance for the Waiting state, even
            // when collapsed — the user shouldn't have to expand a card to
            // get out of the "app is useless" state.
            if (mode == CardMode.Waiting && !isExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onRetry,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = " Retry",
                            color = contentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedBody(backendIdentifier: String, contentColor: Color) {
    Text(
        text = "Another app on this device is managing the Reticulum " +
            "network. Eridanus is using that shared instance — your hub " +
            "traffic flows through it for transport.",
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BulletLine(
            "Common hosts: Sideband, rnsd in Termux, Reticulum-for-Android, Carina.",
            contentColor,
        )
        BulletLine(
            "Your nickname, identities, and message history stay private to Eridanus.",
            contentColor,
        )
        BulletLine(
            "If the host app goes offline, Eridanus auto-recovers when " +
                "it (or another host) comes back.",
            contentColor,
        )
        if (backendIdentifier == "python") {
            BulletLine(
                "Backend: upstream python Reticulum (reference implementation). " +
                    "All in-process crypto and link state runs through the python stack.",
                contentColor,
            )
        }
    }
}

@Composable
private fun ReconnectingBody(contentColor: Color) {
    Text(
        text = "Tearing down and re-probing for a shared Reticulum " +
            "instance on 127.0.0.1:37428. This usually takes a few " +
            "seconds.",
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
    )
}

@Composable
private fun LostHostBody(contentColor: Color) {
    Text(
        text = "The shared Reticulum instance Eridanus was using is no " +
            "longer responding. The watchdog will re-attach automatically " +
            "as soon as it (or another host) is reachable again.",
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
    )
    Text(
        text = "You can also tap Retry below to re-probe right now.",
        style = MaterialTheme.typography.bodySmall,
        color = contentColor.copy(alpha = 0.75f),
    )
}

@Composable
private fun WaitingBody(contentColor: Color, onRetry: () -> Unit) {
    Text(
        text = "Eridanus needs a Reticulum shared instance running on this " +
            "device to discover hubs and send messages. Start one of:",
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BulletLine("Sideband (most common)", contentColor)
        BulletLine("Reticulum-for-Android (python RNS via chaquopy)", contentColor)
        BulletLine("Carina (kotlin reticulum-kt)", contentColor)
        BulletLine("rnsd inside Termux", contentColor)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Text(text = " Retry", color = contentColor)
        }
    }
}

@Composable
private fun BulletLine(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "•", color = color, style = MaterialTheme.typography.bodyMedium)
        Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
