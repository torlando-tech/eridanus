// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discovered_hubs")
data class HubEntity(
    @PrimaryKey val hexHash: String,
    val hash: ByteArray,
    val name: String,
    val lastSeen: Long,
    val starred: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HubEntity) return false
        return hexHash == other.hexHash
    }

    override fun hashCode(): Int = hexHash.hashCode()
}
