// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns

import android.content.Context

interface RnsBackend {
    val identifier: String

    fun start(context: Context, config: RnsBackendConfig)
    fun stop(context: Context)
    val isRunning: Boolean

    val connectedToSharedInstance: Boolean
    fun isSharedInstanceRunning(port: Int = RnsBackendConfig.DEFAULT_SHARED_INSTANCE_PORT): Boolean

    /**
     * Tear the backend's host service all the way down and bring it back
     * up. This is the only way to re-run the shared-instance auto-attach
     * probe — the underlying RNS instance picks its role
     * (attach-as-client vs run-standalone) exactly once at service-start
     * time and has no in-place rebind hook. Used when the user-driven
     * "Retry" banner button fires, and by EridanusViewModel's periodic
     * watchdog when the topology of 127.0.0.1:37428 changes.
     *
     * Suspends until the new instance is running (or the implementation
     * times out — caller should re-check [isRunning] /
     * [connectedToSharedInstance] after suspension). Implementations must
     * be safe to call from non-main threads; callers are expected to
     * serialize across invocations themselves (e.g. with a Mutex).
     */
    suspend fun restart(context: Context, config: RnsBackendConfig)

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
