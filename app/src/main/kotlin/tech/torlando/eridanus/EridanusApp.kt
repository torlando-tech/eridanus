// SPDX-License-Identifier: MPL-2.0

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

        // The RNS-hosting foreground service owns the app's single
        // persistent notification — rns-android's ReticulumService on the
        // kotlin flavor, PyReticulumService on the python flavor — and
        // each creates its own notification channel in onCreate().
        // App-level status ("Connected to hub", …) is pushed into that
        // notification via RnsBackend.setForegroundStatus (python flavor
        // today; kotlin once reticulum-kt grows a status hook).
    }
}
