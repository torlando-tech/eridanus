package tech.torlando.ara

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AraApp : Application() {

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
            description = "Keeps Ara listening for hub announces in the background"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
