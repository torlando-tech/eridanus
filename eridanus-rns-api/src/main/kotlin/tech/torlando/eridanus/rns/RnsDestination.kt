// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns

enum class RnsDestinationDirection { IN, OUT }

enum class RnsDestinationType { SINGLE, GROUP, PLAIN, LINK }

interface RnsDestination {
    val hash: ByteArray
    val hexHash: String
    fun announce(appData: ByteArray?)
    fun setLinkEstablishedCallback(callback: (RnsLink) -> Unit)
}

interface RnsDestinationFactory {
    fun appAndAspectsFromName(name: String): Pair<String, List<String>>?

    fun create(
        identity: RnsIdentity,
        direction: RnsDestinationDirection,
        type: RnsDestinationType,
        appName: String,
        vararg aspects: String,
    ): RnsDestination
}
