// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.components

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import tech.torlando.eridanus.R

private const val GITHUB_URL = "https://github.com/torlando-tech/eridanus"

/**
 * About card — app logo, version, the embedded Reticulum stack, and device
 * info. Modelled on Columba's AboutCard, trimmed to what eridanus surfaces
 * (no update-checker / bug-report infra).
 */
@Composable
fun AboutCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    backendIdentifier: String,
    reticulumVersion: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pkg = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    val appVersion = pkg?.versionName ?: "unknown"
    val buildCode = pkg?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            it.longVersionCode
        } else {
            @Suppress("DEPRECATION") it.versionCode.toLong()
        }
    } ?: 0L

    CollapsibleCard(
        title = "About",
        icon = Icons.Default.Info,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "Eridanus logo",
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = "Eridanus",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "IRC-style chatrooms over Reticulum",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider()

            InfoSection("Application") {
                InfoRow("Version", appVersion)
                InfoRow("Build", buildCode.toString())
            }

            HorizontalDivider()

            InfoSection("Reticulum") {
                InfoRow("Backend", backendIdentifier.replaceFirstChar { it.uppercase() })
                InfoRow("Version", reticulumVersion)
            }

            HorizontalDivider()

            InfoSection("Device") {
                InfoRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }

            HorizontalDivider()

            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri()))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("View on GitHub")
            }
            Text(
                text = "Licensed under MPL-2.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    // Label takes its natural width; value takes the rest and right-aligns,
    // so a long value (e.g. "v0.0.19 (reticulum-kt)" or a long device model)
    // wraps in its column instead of colliding with the label.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
