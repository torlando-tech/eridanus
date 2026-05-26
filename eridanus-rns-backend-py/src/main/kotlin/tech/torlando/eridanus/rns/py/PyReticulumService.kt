// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.py

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.util.concurrent.Executors

/**
 * Foreground service that owns the embedded python Reticulum instance.
 *
 * Mirrors `network.reticulum.android.ReticulumService` (rns-android) at the
 * surface eridanus actually uses — start/stop/getInstance + a getReticulum()
 * accessor (here returning the python `Reticulum` object as a [PyObject]) +
 * `isRunning` / `connectedToSharedInstance`.
 *
 * This is also eridanus's *single* persistent notification on the python
 * flavor — the app no longer runs a separate notification-only service.
 * [updateStatus] sets the user-facing status line; EridanusViewModel
 * drives it through PyRnsBackend.setForegroundStatus.
 *
 * Notably narrower than the kotlin backend's service: no battery observer,
 * no pause/resume, no Room-backed identity/path stores. Reticulum's own
 * file-based stores under `configdir/` are sufficient for the
 * shared-instance-client mode eridanus uses.
 */
class PyReticulumService : Service() {

    @Volatile
    private var reticulum: PyObject? = null

    @Volatile
    private var startInProgress: Boolean = false

    /** User-facing status line shown in the foreground notification.
     * Updated via [updateStatus] from EridanusViewModel's status flow. */
    @Volatile
    private var statusText: String = "Starting…"

    /** Partial wake lock that keeps the CPU scheduling the python RNS
     * threads through Doze. Lazily created on first acquire; guarded by
     * [wakeLockLock]. See [setKeepAliveWakeLock]. */
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockLock = Any()

    /** True between successful `RNS.Reticulum(...)` instantiation and stop. */
    val isRunning: Boolean
        get() = reticulum != null

    /** True when the embedded RNS attached to a shared instance on 37428. */
    val connectedToSharedInstance: Boolean
        get() {
            val r = reticulum ?: return false
            return try {
                val py = Python.getInstance().getModule("event_bridge")
                py.callAttr("is_connected_to_shared_instance", r).toJava(Boolean::class.javaObjectType)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to read is_connected_to_shared_instance", e)
                false
            }
        }

    private fun bootInWorkerThread() {
        if (reticulum != null) {
            // Already booted. onStartCommand fires again on every
            // foreground — a recreated EridanusViewModel calls
            // backend.start() from its init — and on START_STICKY
            // re-delivery. Re-running reticulum_start would
            // reticulum_reset_class_state() + RNS.Reticulum() the *live*
            // instance: tearing down and rebuilding it, dropping the
            // shared-instance attachment and every registered announce
            // handler. Make re-invocation a no-op.
            Log.d(TAG, "bootInWorkerThread: Reticulum already running — skipping re-init")
            return
        }
        if (startInProgress) return
        startInProgress = true
        worker.execute {
            try {
                // Probe 127.0.0.1:37428 here, not from the watchdog, so the
                // probe and the boot can't disagree across a slow restart
                // window. If a host is present we want share_instance = yes
                // in the config (forcing RNS through its server-bind →
                // bind-fails → LocalClientInterface fallback path); if no
                // host is present we want share_instance = no so we never
                // accidentally become the shared-instance server ourselves
                // (the "role inversion" eridanus deliberately avoids).
                val hostPresent = probeSharedInstance()
                val py = Python.getInstance()
                val bridge = py.getModule("event_bridge")
                val configdir = bridge.callAttr(
                    "reticulum_config_dir",
                    filesDir.absolutePath,
                    hostPresent,
                )
                Log.i(
                    TAG,
                    "Booting Reticulum (configdir=${configdir}, host_present=$hostPresent)",
                )
                val r = bridge.callAttr("reticulum_start", configdir)
                reticulum = r
                Log.i(TAG, "Reticulum booted successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to boot Reticulum", e)
            } finally {
                startInProgress = false
            }
        }
    }

    private fun probeSharedInstance(): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 37428), 1_000)
            true
        }
    } catch (_: Exception) {
        false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        bootInWorkerThread()
        return START_STICKY
    }

    override fun onDestroy() {
        setKeepAliveWakeLock(false)
        try {
            val r = reticulum
            if (r != null) {
                Log.i(TAG, "Shutting down Reticulum")
                // Delegate to event_bridge.reticulum_shutdown — that's where
                // the full teardown sequence (detach interfaces → persist
                // tables → exit_handler → reset class-level state) lives.
                // Just calling RNS.Reticulum.exit_handler isn't enough on
                // its own: Transport's class-level destination/interface
                // lists survive across exit_handler and trip "already
                // registered destination" errors on the next watchdog
                // restart. Same pattern columba v0.10.x's
                // reticulum_wrapper.shutdown uses.
                Python.getInstance().getModule("event_bridge")
                    .callAttr("reticulum_shutdown", r)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Reticulum shutdown failed", e)
        } finally {
            reticulum = null
            instance = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Update the status line shown in the foreground notification and
     * re-post it. Called from PyRnsBackend.setForegroundStatus, which
     * EridanusViewModel drives off its connection-status flow. Safe to
     * call from any thread (NotificationManager.notify is thread-safe;
     * [statusText] is @Volatile).
     */
    fun updateStatus(text: String) {
        statusText = text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * Acquire or release the keep-alive partial wake lock.
     *
     * The foreground service keeps this *process* alive but not the *CPU* —
     * once the device suspends in Doze, the python RNS threads stop running,
     * the LocalClientInterface socket + RRC link keepalive go silent, and the
     * hub tears the link down (no PONG within its 30s ping timeout), dropping
     * the user out of their room. A PARTIAL_WAKE_LOCK keeps those threads
     * scheduled; combined with the battery-optimization exemption (which
     * stops Doze from deferring the lock) the link survives idle.
     *
     * Driven by EridanusViewModel from (user "keep connection alive" setting
     * && connected to a hub). The lock is non-reference-counted, so repeated
     * acquire/release calls collapse to a single held/unheld state and this
     * is safe to call on every connection-state change. Thread-safe via
     * [wakeLockLock].
     */
    @Suppress("WakelockTimeout") // intentionally indefinite; released on
    // disconnect / toggle-off / onDestroy, gated behind a user opt-in.
    fun setKeepAliveWakeLock(held: Boolean) {
        synchronized(wakeLockLock) {
            if (held) {
                val lock = wakeLock ?: run {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                        .also { it.setReferenceCounted(false); wakeLock = it }
                }
                if (!lock.isHeld) {
                    lock.acquire()
                    Log.i(TAG, "Keep-alive wake lock acquired")
                }
            } else {
                wakeLock?.takeIf { it.isHeld }?.let {
                    it.release()
                    Log.i(TAG, "Keep-alive wake lock released")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Eridanus",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Eridanus connected to the Reticulum network"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Content intent: open the app. Resolved via the launcher intent
        // rather than a direct MainActivity reference — MainActivity lives
        // in :app and isn't visible from this backend module.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Eridanus")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification_eridanus)
            .setOngoing(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val TAG = "PyReticulumService"
        private const val CHANNEL_ID = "reticulum_py"
        private const val NOTIFICATION_ID = 0xCA1F  // arbitrary, low-collision
        private const val WAKE_LOCK_TAG = "eridanus:keep-alive"

        private val worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "py-rns-boot").apply { isDaemon = true }
        }

        @Volatile
        private var instance: PyReticulumService? = null

        fun getInstance(): PyReticulumService? = instance

        fun start(context: Context) {
            val intent = Intent(context, PyReticulumService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PyReticulumService::class.java))
        }
    }
}
