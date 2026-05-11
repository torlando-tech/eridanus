package tech.torlando.eridanus

import android.app.Application

class EridanusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Notification channels are created lazily by their owning services:
        //   - `network.reticulum.android.ReticulumService` creates the "reticulum"
        //     channel for the rns-android foreground service in its onCreate().
        //   - `tech.torlando.eridanus.service.EridanusConnectionService` creates
        //     "eridanus_connection" for app-domain status updates.
    }
}
