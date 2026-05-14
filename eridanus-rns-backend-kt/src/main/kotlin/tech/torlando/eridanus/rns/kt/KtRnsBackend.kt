package tech.torlando.eridanus.rns.kt

import android.content.Context
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
        get() = ReticulumService.getInstance()?.getReticulum()?.connectToSharedInstance == true

    override fun isSharedInstanceRunning(port: Int): Boolean =
        Reticulum.isSharedInstanceRunning(port)

    override fun reconnectInterfaces() {
        ReticulumService.getInstance()?.reconnectInterfaces()
    }

    override val identities = KtRnsIdentityFactory
    override val destinations = KtRnsDestinationFactory
    override val links = KtRnsLinkFactory
    override val resources = KtRnsResourceFactory
    override val transport = KtRnsTransport
}
