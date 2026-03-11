package tech.torlando.eridanus

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.Transport

/**
 * Foreground service that keeps Reticulum running and listening for hub
 * announces even while the app is in the background.
 */
class ReticulumService : Service() {

    companion object {
        const val CHANNEL_ID = "eridanus_reticulum"
        const val NOTIFICATION_ID = 1
        private const val TAG = "ReticulumService"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _reticulumStarted = MutableStateFlow(false)
    val reticulumStarted: StateFlow<Boolean> = _reticulumStarted

    private val _connectedToSharedInstance = MutableStateFlow(false)
    val connectedToSharedInstance: StateFlow<Boolean> = _connectedToSharedInstance

    var onHubAnnounce: ((destHash: ByteArray, appData: ByteArray?) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): ReticulumService = this@ReticulumService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Starting Reticulum…"))
        serviceScope.launch {
            startReticulum()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startReticulum() {
        try {
            Reticulum.start()
            _reticulumStarted.value = true

            val connected = tryConnectSharedInstance()
            _connectedToSharedInstance.value = connected
            Log.i(TAG, "Reticulum started (shared instance: $connected)")

            registerAnnounceHandler()

            val statusText = if (connected) {
                "Listening via shared instance"
            } else {
                "Listening (standalone)"
            }
            updateNotification(statusText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Reticulum", e)
            updateNotification("Failed to start")
        }
    }

    private fun tryConnectSharedInstance(): Boolean {
        val port = 37428
        if (!Reticulum.isSharedInstanceRunning(port)) {
            Log.w(TAG, "No shared instance on port $port")
            return false
        }
        return try {
            val client = LocalClientInterface(
                name = "SharedInstanceClient",
                tcpPort = port,
            )
            client.start()
            Transport.registerInterface(InterfaceAdapter.getOrCreate(client))
            Log.i(TAG, "Connected to shared instance on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to shared instance", e)
            false
        }
    }

    private fun registerAnnounceHandler() {
        try {
            val handler = AnnounceHandler { destHash, _, appData ->
                onHubAnnounce?.invoke(destHash, appData)
                true
            }
            Transport.registerAnnounceHandler(handler)
            Log.d(TAG, "Announce handler registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register announce handler", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eridanus")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }
}
