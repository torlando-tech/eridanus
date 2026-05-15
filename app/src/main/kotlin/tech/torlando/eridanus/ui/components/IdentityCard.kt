// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.torlando.eridanus.viewmodel.EridanusViewModel
import tech.torlando.eridanus.viewmodel.IdentityImportResult

/**
 * Settings card to export and import the user's client identity.
 *
 * Wire-compatible with Sideband and columba: the share-sheet/paste format
 * is Base32(RFC 4648) of the 64-byte RNS Identity private key, and the
 * file format is the same 64 raw bytes that `RNS.Identity.to_file` writes.
 *
 * UX modeled on Sideband's "Backup & Keys" screen (sbapp/ui/keys.py) — a
 * caution banner, display/copy/share for export, and a confirmed
 * destructive paste-or-file for import. Eridanus does the swap in-process
 * (no app restart needed), unlike Sideband which exits after import.
 */
@Composable
fun IdentityCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    viewModel: EridanusViewModel,
) {
    val context = LocalContext.current
    val hashHex by viewModel.clientIdentityHashHex.collectAsState()

    var showKeyDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingFileBytes by remember { mutableStateOf<ByteArray?>(null) }

    // Toast import outcomes.
    LaunchedEffect(Unit) {
        viewModel.identityImportResult.collect { result ->
            val msg = when (result) {
                IdentityImportResult.Success ->
                    "Identity imported — any active hub connection has been dropped"
                is IdentityImportResult.Failure -> "Import failed: ${result.message}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        if (bytes == null || bytes.size != 64) {
            Toast.makeText(
                context,
                "Selected file is not a valid 64-byte Reticulum identity",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            pendingFileBytes = bytes
            showImportDialog = true
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = viewModel.exportClientIdentityBytes()
        if (bytes == null) {
            Toast.makeText(context, "No identity to export", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(context, "Identity saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!isExpanded) },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = null,
                    )
                    Column {
                        Text(
                            text = "Identity",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = hashHex?.take(16)?.let { "$it…" } ?: "Loading…",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(durationMillis = 300)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 300)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Address",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = hashHex ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )

                    Text(
                        text = "Anyone with your identity key can impersonate you and " +
                            "read your messages. Keep exported keys secret.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider()

                    Text(
                        text = "Export",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedButton(
                        onClick = { showKeyDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Display & share key")
                    }
                    OutlinedButton(
                        onClick = { saveFileLauncher.launch("eridanus_identity.rid") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save to file")
                    }

                    HorizontalDivider()

                    Text(
                        text = "Import",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "Replaces your current identity. Any active hub " +
                            "connection will be dropped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            pendingFileBytes = null
                            showImportDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Paste Base32 key")
                    }
                    OutlinedButton(
                        onClick = { openFileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import from file")
                    }
                }
            }
        }
    }

    if (showKeyDialog) {
        DisplayKeyDialog(
            base32Key = viewModel.exportClientIdentityBase32(),
            onDismiss = { showKeyDialog = false },
            onCopy = { key ->
                copyToClipboard(context, key)
                Toast.makeText(context, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onShare = { key -> shareText(context, key) },
        )
    }

    if (showImportDialog) {
        ImportKeyDialog(
            preloadedBytes = pendingFileBytes,
            onDismiss = {
                showImportDialog = false
                pendingFileBytes = null
            },
            onConfirmText = { text ->
                showImportDialog = false
                pendingFileBytes = null
                viewModel.importClientIdentityFromBase32(text)
            },
            onConfirmBytes = { bytes ->
                showImportDialog = false
                pendingFileBytes = null
                viewModel.importClientIdentity(bytes)
            },
        )
    }
}

@Composable
private fun DisplayKeyDialog(
    base32Key: String?,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Identity key (Base32)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Anyone with this key can control your address. Do not " +
                        "share screen, copy, or transmit insecurely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = base32Key ?: "No identity available",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (base32Key != null) {
                    TextButton(onClick = { onShare(base32Key) }) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share")
                    }
                    TextButton(onClick = { onCopy(base32Key) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun ImportKeyDialog(
    preloadedBytes: ByteArray?,
    onDismiss: () -> Unit,
    onConfirmText: (String) -> Unit,
    onConfirmBytes: (ByteArray) -> Unit,
) {
    var pasted by remember { mutableStateOf("") }
    val fromFile = preloadedBytes != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fromFile) "Import identity from file" else "Import identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Your current identity will be irreversibly replaced. " +
                        "Make sure you have a backup if you might need to restore it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (fromFile) {
                    Text(
                        text = "Selected file is 64 bytes — looks like a valid " +
                            "Reticulum identity. Tap Import to proceed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("Paste Base32 key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (fromFile && preloadedBytes != null) {
                        onConfirmBytes(preloadedBytes)
                    } else if (pasted.isNotBlank()) {
                        onConfirmText(pasted)
                    }
                },
                enabled = fromFile || pasted.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Eridanus identity key", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(intent, "Share identity key").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
