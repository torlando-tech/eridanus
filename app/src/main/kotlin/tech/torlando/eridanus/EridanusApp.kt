package tech.torlando.eridanus

import android.app.Application
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.provideRnsBackend

class EridanusApp : Application() {

    lateinit var rnsBackend: RnsBackend
        private set

    override fun onCreate() {
        super.onCreate()
        // The :app module is flavor-neutral. provideRnsBackend(...) resolves
        // to the per-flavor implementation in app/src/kotlin/... or
        // app/src/python/..., which returns either KtRnsBackend or
        // PyRnsBackend. Everything downstream (ViewModels, RRC client/hub,
        // identity store) consumes the backend through this field — no
        // direct imports of network.reticulum.* anywhere outside the
        // :eridanus-rns-backend-kt module.
        rnsBackend = provideRnsBackend(this)

        // Notification channels are created lazily by their owning services:
        //   - the rns-android foreground service creates the "reticulum"
        //     channel in its onCreate().
        //   - `tech.torlando.eridanus.service.EridanusConnectionService`
        //     creates "eridanus_connection" for app-domain status updates.
    }
}
