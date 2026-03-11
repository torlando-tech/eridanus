package tech.torlando.eridanus

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class EridanusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            ReticulumService.CHANNEL_ID,
            "Reticulum Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Eridanus listening for hub announces in the background"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
