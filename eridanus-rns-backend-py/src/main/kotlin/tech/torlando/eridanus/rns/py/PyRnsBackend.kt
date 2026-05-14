package tech.torlando.eridanus.rns.py

import android.content.Context
import tech.torlando.eridanus.rns.RnsAnnounceHandler
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.RnsBackendConfig
import tech.torlando.eridanus.rns.RnsDestination
import tech.torlando.eridanus.rns.RnsDestinationDirection
import tech.torlando.eridanus.rns.RnsDestinationFactory
import tech.torlando.eridanus.rns.RnsDestinationType
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsIdentityFactory
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsLinkFactory
import tech.torlando.eridanus.rns.RnsResource
import tech.torlando.eridanus.rns.RnsResourceFactory
import tech.torlando.eridanus.rns.RnsTransport

class PyRnsBackend : RnsBackend {
    override val identifier: String = "python"

    override fun start(context: Context, config: RnsBackendConfig): Nothing = notWiredYet()
    override fun stop(context: Context): Nothing = notWiredYet()
    override val isRunning: Boolean get() = notWiredYet()
    override val connectedToSharedInstance: Boolean get() = notWiredYet()
    override fun isSharedInstanceRunning(port: Int): Boolean = notWiredYet()
    override fun reconnectInterfaces(): Nothing = notWiredYet()

    override val identities: RnsIdentityFactory = NotWired
    override val destinations: RnsDestinationFactory = NotWired
    override val links: RnsLinkFactory = NotWired
    override val resources: RnsResourceFactory = NotWired
    override val transport: RnsTransport = NotWired

    private object NotWired : RnsIdentityFactory, RnsDestinationFactory, RnsLinkFactory, RnsResourceFactory, RnsTransport {
        override fun create(): RnsIdentity = notWiredYet()
        override fun recall(destHash: ByteArray): RnsIdentity? = notWiredYet()
        override fun fromBytes(bytes: ByteArray): RnsIdentity? = notWiredYet()

        override fun appAndAspectsFromName(name: String): Pair<String, List<String>>? = notWiredYet()
        override fun create(
            identity: RnsIdentity,
            direction: RnsDestinationDirection,
            type: RnsDestinationType,
            appName: String,
            vararg aspects: String,
        ): RnsDestination = notWiredYet()

        override fun create(
            destination: RnsDestination,
            establishedCallback: (RnsLink) -> Unit,
            closedCallback: (RnsLink) -> Unit,
        ): RnsLink = notWiredYet()

        override fun create(
            data: ByteArray,
            link: RnsLink,
            advertise: Boolean,
            autoCompress: Boolean,
            requestId: ByteArray?,
        ): RnsResource = notWiredYet()

        override fun findDestination(hash: ByteArray): RnsDestination? = notWiredYet()
        override fun requestPath(hash: ByteArray): Nothing = notWiredYet()
        override fun hasPath(hash: ByteArray): Boolean = notWiredYet()
        override fun registerDestination(destination: RnsDestination): Nothing = notWiredYet()
        override fun deregisterDestination(destination: RnsDestination): Nothing = notWiredYet()
        override fun registerAnnounceHandler(handler: RnsAnnounceHandler): Nothing = notWiredYet()
    }

    companion object {
        private fun notWiredYet(): Nothing = throw NotImplementedError(
            "PyRnsBackend is scaffolded but not wired. " +
                "Chaquopy + upstream RNS bundle are pending; see " +
                "Memory/eridanus/rns-backend-dual-build.md for the plan."
        )
    }
}
