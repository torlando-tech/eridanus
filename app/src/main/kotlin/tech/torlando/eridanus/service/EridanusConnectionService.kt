package tech.torlando.eridanus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import tech.torlando.eridanus.MainActivity
import tech.torlando.eridanus.R

class EridanusConnectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "eridanus_connection"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TEXT = "text"

        private var instance: EridanusConnectionService? = null
        private var pendingText: String? = null

        fun start(context: Context) {
            val intent = Intent(context, EridanusConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EridanusConnectionService::class.java))
        }

        fun updateStatus(text: String) {
            val svc = instance
            if (svc != null) {
                svc.updateNotification(text)
            } else {
                pendingText = text
            }
        }
    }

    private var currentText = "Starting\u2026"

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        pendingText?.let {
            currentText = it
            pendingText = null
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TEXT)?.let { currentText = it }
        val notification = buildNotification(currentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(text: String) {
        currentText = text
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Eridanus Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Eridanus connected to the Reticulum network"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eridanus")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
