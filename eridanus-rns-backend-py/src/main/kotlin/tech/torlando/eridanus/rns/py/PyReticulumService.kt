// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.py

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
 * `isRunning` / `connectedToSharedInstance` / `reconnectInterfaces()`.
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reticulum (python)",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Eridanus python flavor — embedded Reticulum runtime"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Reticulum")
            .setContentText("python flavor — embedded RNS")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "PyReticulumService"
        private const val CHANNEL_ID = "reticulum_py"
        private const val NOTIFICATION_ID = 0xCA1F  // arbitrary, low-collision

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
