package tech.torlando.eridanus.rns.py

import android.content.Context
import com.chaquo.python.Python
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
        // Bring up Reticulum on the host service. RnsBackendConfig.shareInstance
        // is intentionally not honored on the python backend yet — eridanus
        // is shared-instance-client-only and event_bridge.reticulum_config_dir
        // hardcodes that. (Wiring shareInstance through later is a few-line
        // change in the config writer.) Treat shareInstance=true as a misuse
        // for now.
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

    override fun reconnectInterfaces() {
        PyReticulumService.getInstance()?.reconnectInterfaces()
    }

    override val identities = PyRnsIdentityFactory(rns)
    override val destinations = PyRnsDestinationFactory(rns)
    override val links = PyRnsLinkFactory(rns)
    override val resources = PyRnsResourceFactory(rns)
    override val transport = PyRnsTransport(rns)
}
