// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import tech.torlando.eridanus.viewmodel.EridanusViewModel
import tech.torlando.eridanus.viewmodel.IdentityImportResult

/**
 * Reusable "import a Reticulum identity" affordance: a paste-Base32 button and
 * an import-from-file button, plus the confirmation dialog and result toast.
 *
 * Shared by [IdentityCard] (Settings) and the onboarding nickname page so the
 * Sideband/columba-compatible import flow lives in exactly one place. The
 * Base32 paste and 64-byte file formats are handled by the ViewModel
 * ([EridanusViewModel.importClientIdentityFromBase32] /
 * [EridanusViewModel.importClientIdentity]); this composable only drives the UI.
 */
@Composable
internal fun IdentityImportControls(
    viewModel: EridanusViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
