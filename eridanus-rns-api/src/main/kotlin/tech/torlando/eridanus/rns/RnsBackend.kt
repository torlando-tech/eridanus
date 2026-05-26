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

    /**
     * Set the user-facing status line shown in the backend's
     * foreground-service notification — e.g. "Connected to hub",
     * "Hosting hub · 3 connected", "Listening (standalone)".
     *
     * The RNS-hosting foreground service is the app's single persistent
     * notification (eridanus no longer runs a separate notification-only
     * service). The python backend reflects this text directly in
     * PyReticulumService's notification. The kotlin backend currently
     * no-ops: rns-android's ReticulumService builds its own notification
     * from a ConnectionSnapshot and exposes no app-status injection hook
     * yet — tracked as a reticulum-kt follow-up (see the Obsidian note
     * "reticulum-kt — ReticulumService app-status notification hook").
     * Until that lands, the kotlin flavor shows rns-android's own
     * network-status notification.
     *
     * Safe to call from any thread; cheap enough to call on every status
     * change.
     */
    fun setForegroundStatus(text: String)

    /**
     * Acquire ([held] = true) or release ([held] = false) a partial wake
     * lock that keeps the CPU scheduling the backend's network threads while
     * the device is in Doze / deep idle.
     *
     * The hosting foreground service keeps the *process* alive; it does not
     * keep the *CPU* running. Without this lock an idle device suspends the
     * SoC, the RNS interface + link-keepalive threads freeze, and the hub
     * tears the link down (no keepalive within its timeout) — silently
     * dropping the user out of their room. Holding a partial wake lock,
     * together with the battery-optimization exemption that stops Doze from
     * deferring it, keeps those threads ticking.
     *
     * This is a continuous battery cost, so callers gate it behind the user's
     * "keep connection alive in background" setting and only hold it while
     * actually connected. The python backend forwards this to
     * PyReticulumService; the kotlin backend currently no-ops (rns-android's
     * ReticulumService manages its own locks).
     *
     * Idempotent and safe to call from any thread.
     */
    fun setKeepAliveWakeLock(held: Boolean)

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
