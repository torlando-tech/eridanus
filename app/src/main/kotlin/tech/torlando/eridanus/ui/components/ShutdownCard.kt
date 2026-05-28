// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tech.torlando.eridanus.viewmodel.EridanusViewModel

/**
 * Settings card that fully shuts Eridanus down — disconnect from any hub,
 * stop the RNS-hosting foreground service, finish the Activity, and kill
 * the process. After tapping there is no background presence at all: no
 * notification, no service, no announce listener. Reticulum stays down
 * until the user reopens the app.
 *
 * Modelled on Columba's ServiceControlCard (network/columba/app/ui/screens/
 * IdentityScreen.kt) but takes the *app* down rather than just the service.
 * Eridanus is a single-purpose chat client; once the user has decided to
 * leave, killProcess is the only way to guarantee nothing silently
 * respawns — Android's foreground-service restart, or JIT-loaded python
 * worker threads on the python flavor — without it the process can sit in
 * the cached-state pool with embedded RNS state still loaded.
 *
 * Collapsible like the other settings cards so the destructive button
 * isn't a one-tap target — the user has to expand the card first, then
 * tap the button, then confirm in the dialog.
 */
@Composable
fun ShutdownCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    viewModel: EridanusViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var shuttingDown by remember { mutableStateOf(false) }

    CollapsibleCard(
        title = "Shutdown",
        icon = Icons.Default.PowerSettingsNew,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        Text(
            text = "Disconnect, stop the Reticulum service, and exit " +
                "the app. Nothing keeps running in the background " +
                "until you reopen Eridanus.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { showConfirm = true },
            enabled = !shuttingDown,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (shuttingDown) "Shutting down…" else "Shut Down Eridanus")
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Shut down Eridanus?") },
            text = {
                Text(
                    "Disconnects from any active hub, stops the Reticulum " +
                        "service, and exits. You won't receive announces or " +
                        "messages until you reopen the app.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        shuttingDown = true
                        viewModel.fullShutdown {
                            // Drop the task from recents so the user sees a
                            // real "gone" state; killProcess then tears the
                            // JVM (and embedded python) down so nothing is
                            // left cached. Both are required: finish alone
                            // leaves the process around; killProcess alone
                            // can leave a recents entry on some OEMs.
                            (context as? Activity)?.finishAndRemoveTask()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    },
                ) { Text("Shut down") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
