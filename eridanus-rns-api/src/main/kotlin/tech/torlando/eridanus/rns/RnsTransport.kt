// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns

fun interface RnsAnnounceHandler {
    fun onAnnounce(destHash: ByteArray, announcedIdentity: RnsIdentity?, appData: ByteArray?): Boolean
}

interface RnsTransport {
    fun findDestination(hash: ByteArray): RnsDestination?
    fun requestPath(hash: ByteArray)
    fun hasPath(hash: ByteArray): Boolean

    fun registerDestination(destination: RnsDestination)
    fun deregisterDestination(destination: RnsDestination)
    fun registerAnnounceHandler(handler: RnsAnnounceHandler)
}
