package tech.torlando.eridanus.rns

import android.content.Context

interface RnsBackend {
    val identifier: String

    fun start(context: Context, config: RnsBackendConfig)
    fun stop(context: Context)
    val isRunning: Boolean

    val connectedToSharedInstance: Boolean
    fun isSharedInstanceRunning(port: Int = RnsBackendConfig.DEFAULT_SHARED_INSTANCE_PORT): Boolean
    fun reconnectInterfaces()

    val identities: RnsIdentityFactory
    val destinations: RnsDestinationFactory
    val links: RnsLinkFactory
    val resources: RnsResourceFactory
    val transport: RnsTransport
}

data class RnsBackendConfig(
    val shareInstance: Boolean = false,
    val sharedInstancePort: Int = DEFAULT_SHARED_INSTANCE_PORT,
) {
    companion object {
        const val DEFAULT_SHARED_INSTANCE_PORT: Int = 37428
    }
}
