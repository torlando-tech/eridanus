// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.kt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import network.reticulum.Reticulum
import network.reticulum.android.ReticulumConfig
import network.reticulum.android.ReticulumService
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.RnsBackendConfig

class KtRnsBackend : RnsBackend {
    override val identifier: String = "kotlin"

    override fun start(context: Context, config: RnsBackendConfig) {
        ReticulumService.start(
            context,
            ReticulumConfig(
                shareInstance = config.shareInstance,
                sharedInstancePort = config.sharedInstancePort,
            ),
        )
    }

    override fun stop(context: Context) {
        ReticulumService.stop(context)
    }

    override val isRunning: Boolean
        get() = ReticulumService.getInstance()?.isRunning() == true

    override val connectedToSharedInstance: Boolean
        // `isConnectedToSharedInstance` is the runtime result of
        // tryConnectToSharedInstance — i.e. did the LocalClientInterface
        // actually come up? — not `connectToSharedInstance`, which is the
        // constructor-time intent passed in based on a port probe. The
        // intent can be true while the runtime attach failed, which would
        // have the UI saying "attached" while no client interface
        // existed. Use the runtime flag so the EridanusViewModel
        // watchdog never loops on a half-attached state.
        get() = ReticulumService.getInstance()?.getReticulum()?.isConnectedToSharedInstance == true

    override fun isSharedInstanceRunning(port: Int): Boolean =
        Reticulum.isSharedInstanceRunning(port)

    override suspend fun restart(context: Context, config: RnsBackendConfig) {
        // rns-android's ReticulumService picks its role (attach-as-client
        // vs standalone) exactly once at onStartCommand and exposes no
        // in-place rebind hook. The only way to re-run the auto-attach
        // probe is to stop the service entirely, wait for onDestroy to
        // clear the static instance pointer, and start fresh.
        Log.i(TAG, "Restarting Reticulum to re-run shared-instance attach probe")
        stop(context)

        // Wait for ReticulumService.onDestroy to null out its static
        // instance pointer. 5s ceiling mirrors the StoreLifecycle drain
        // budget inside rns-android, which dominates teardown time.
        val teardownDeadline = System.currentTimeMillis() + TEARDOWN_TIMEOUT_MS
        while (System.currentTimeMillis() < teardownDeadline &&
            ReticulumService.getInstance() != null
        ) {
            delay(POLL_INTERVAL_MS)
        }
        // Extra grace for socket cleanup so the next probe sees either
        // the new host bound or nothing bound — never a half-closed
        // socket from a previous attempt.
        delay(POST_TEARDOWN_GRACE_MS)

        start(context, config)
        // Give the freshly-started service the same 2s window the initial
        // bring-up uses before its isRunning / getReticulum() flip true.
        delay(START_GRACE_MS)
    }

    override val identities = KtRnsIdentityFactory
    override val destinations = KtRnsDestinationFactory
    override val links = KtRnsLinkFactory
    override val resources = KtRnsResourceFactory
    override val transport = KtRnsTransport

    companion object {
        private const val TAG = "KtRnsBackend"
        private const val TEARDOWN_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 100L
        private const val POST_TEARDOWN_GRACE_MS = 300L
        private const val START_GRACE_MS = 2_000L
    }
}
