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

    /**
     * Bring up Reticulum's interfaces again — used after the user starts a
     * shared-instance host post-launch. The python equivalent of rns-android's
     * `reconnectInterfaces()`. For now this is best-effort; if a deeper
     * re-init turns out to be needed it lives behind this method.
     */
    fun reconnectInterfaces() {
        val r = reticulum ?: return
        try {
            // RNS.Transport doesn't expose a public "reconnect all interfaces"
            // hook the way rns-android does. As a v0 implementation, re-running
            // the LocalClientInterface attempt is implicit in the shared-
            // instance discovery loop RNS already runs internally — calling
            // detach + reattach via Transport is the right escalation if this
            // proves insufficient. Tracked in
            // Memory/eridanus/rns-backend-dual-build.md.
            Log.i(TAG, "reconnectInterfaces called — relying on RNS internal discovery")
        } catch (e: Throwable) {
            Log.w(TAG, "reconnectInterfaces failed", e)
        }
    }

    private fun bootInWorkerThread() {
        if (startInProgress) return
        startInProgress = true
        worker.execute {
            try {
                val py = Python.getInstance()
                val bridge = py.getModule("event_bridge")
                val configdir = bridge.callAttr("reticulum_config_dir", filesDir.absolutePath)
                Log.i(TAG, "Booting Reticulum with configdir=${configdir.toString()}")
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
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
                // RNS.Reticulum.exit_handler is the cleanest shutdown — same
                // path the python sigint/sigterm handlers take.
                Python.getInstance().getModule("RNS")
                    .get("Reticulum")!!
                    .callAttr("exit_handler")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "exit_handler failed", e)
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
