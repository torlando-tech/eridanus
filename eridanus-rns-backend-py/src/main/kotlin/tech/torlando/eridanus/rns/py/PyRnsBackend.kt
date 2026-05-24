// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.py

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.delay
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.RnsBackendConfig

class PyRnsBackend(context: Context) : RnsBackend {

    init {
        // PyRnsBackend assumes Python has already been started (by the
        // per-flavor RnsBackendProvider). It's tolerable to call Python
        // .start again — chaquopy's start() is idempotent — but doing it
        // here would couple the backend to the Application's class loader
        // earlier than necessary.
        require(Python.isStarted()) {
            "Python interpreter must be started before constructing PyRnsBackend. " +
                "Call provideRnsBackend(context) from the python flavor source set."
        }
    }

    private val appContext = context.applicationContext
    private val python = Python.getInstance()
    private val rns = python.getModule("RNS")

    override val identifier: String = "python"

    override fun start(context: Context, config: RnsBackendConfig) {
        // shareInstance=true would mean "deliberately become the shared-
        // instance server"; eridanus doesn't expose that, the service itself
        // toggles share_instance in the config based on the runtime TCP
        // probe (see PyReticulumService.bootInWorkerThread).
        require(!config.shareInstance) {
            "PyRnsBackend currently supports shared-instance-client mode only " +
                "(matches the kotlin flavor's default in this app)."
        }
        PyReticulumService.start(context)
    }

    override fun stop(context: Context) {
        PyReticulumService.stop(context)
    }

    override val isRunning: Boolean
        get() = PyReticulumService.getInstance()?.isRunning == true

    override val connectedToSharedInstance: Boolean
        get() = PyReticulumService.getInstance()?.connectedToSharedInstance == true

    override fun isSharedInstanceRunning(port: Int): Boolean {
        // Upstream RNS doesn't expose a "is_shared_instance_running" probe;
        // the way eridanus uses this is "is somebody listening on the
        // local-instance port?". A trivial socket connect attempt suffices
        // and avoids dragging python into a synchronous check on the UI
        // path.
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun restart(context: Context, config: RnsBackendConfig) {
        // Upstream Reticulum decides its shared-instance role at
        // RNS.Reticulum.__init__ time and exposes no in-place rebind hook,
        // so the only way to re-run the auto-attach probe is a full
        // service teardown + bring-up. PyReticulumService.onDestroy calls
        // RNS.Reticulum.exit_handler() which is what releases the embedded
        // interpreter's RNS state cleanly.
        Log.i(TAG, "Restarting Reticulum (python) to re-run shared-instance attach probe")
        stop(context)

        val teardownDeadline = System.currentTimeMillis() + TEARDOWN_TIMEOUT_MS
        while (System.currentTimeMillis() < teardownDeadline &&
            PyReticulumService.getInstance() != null
        ) {
            delay(POLL_INTERVAL_MS)
        }
        delay(POST_TEARDOWN_GRACE_MS)

        start(context, config)
        delay(START_GRACE_MS)
    }

    override fun setForegroundStatus(text: String) {
        // PyReticulumService's foreground notification is eridanus's single
        // persistent notification on the python flavor — reflect the
        // app-level status line straight into it.
        PyReticulumService.getInstance()?.updateStatus(text)
    }

    override fun setKeepAliveWakeLock(held: Boolean) {
        // The service owns the wake lock (same module as the WAKE_LOCK
        // permission, and it outlives the ViewModel). If the service isn't
        // running there's no RNS instance to keep alive, so dropping the
        // call is correct.
        PyReticulumService.getInstance()?.setKeepAliveWakeLock(held)
    }

    override val identities = PyRnsIdentityFactory(rns)
    override val destinations = PyRnsDestinationFactory(rns)
    override val links = PyRnsLinkFactory(rns)
    override val resources = PyRnsResourceFactory(rns)
    override val transport = PyRnsTransport(rns)

    companion object {
        private const val TAG = "PyRnsBackend"
        private const val TEARDOWN_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 100L
        private const val POST_TEARDOWN_GRACE_MS = 300L
        private const val START_GRACE_MS = 2_500L
    }
}
