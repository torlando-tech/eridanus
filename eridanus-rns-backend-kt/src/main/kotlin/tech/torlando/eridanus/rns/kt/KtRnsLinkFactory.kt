// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.kt

import network.reticulum.link.Link
import tech.torlando.eridanus.rns.RnsDestination
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsLinkFactory

object KtRnsLinkFactory : RnsLinkFactory {
    override fun create(
        destination: RnsDestination,
        establishedCallback: (RnsLink) -> Unit,
        closedCallback: (RnsLink) -> Unit,
    ): RnsLink {
        val link = Link.create(
            destination = destination.asKt(),
            establishedCallback = { established -> establishedCallback(KtRnsLink(established)) },
            closedCallback = { closed -> closedCallback(KtRnsLink(closed)) },
        )
        return KtRnsLink(link)
    }
}
