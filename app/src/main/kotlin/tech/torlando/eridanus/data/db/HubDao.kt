// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HubDao {
    @Query("SELECT * FROM discovered_hubs ORDER BY starred DESC, lastSeen DESC")
    fun observeAll(): Flow<List<HubEntity>>

    @Query(
        "INSERT INTO discovered_hubs (hexHash, hash, name, lastSeen, starred) VALUES (:hexHash, :hash, :name, :lastSeen, 0) " +
        "ON CONFLICT(hexHash) DO UPDATE SET name = :name, lastSeen = :lastSeen"
    )
    suspend fun upsertPreserveStarred(hexHash: String, hash: ByteArray, name: String, lastSeen: Long)

    /**
     * Insert or update a hub row from the manual "add server" flow.
     * New rows get the [name] placeholder; on conflict the existing name is
     * PRESERVED (it may already be the hub's real announced name — the user
     * re-entering a discovered hash must not clobber it) while [starred]
     * and lastSeen are set explicitly, so the dialog's favorite checkbox
     * always wins for the star (issue #42).
     */
    @Query(
        "INSERT INTO discovered_hubs (hexHash, hash, name, lastSeen, starred) VALUES (:hexHash, :hash, :name, :lastSeen, :starred) " +
        "ON CONFLICT(hexHash) DO UPDATE SET lastSeen = :lastSeen, starred = :starred"
    )
    suspend fun upsertHub(hexHash: String, hash: ByteArray, name: String, lastSeen: Long, starred: Boolean)

    @Query("UPDATE discovered_hubs SET starred = NOT starred WHERE hexHash = :hexHash")
    suspend fun toggleStarred(hexHash: String)

    /**
     * Set the display name for an already-present hub row. Used by the
     * WELCOME handshake: when a connected hub greets us with its own name,
     * that is the most authoritative source we have — more reliable than
     * announce app_data, which is absent whenever the path to the hub was
     * already cached (no fresh PATH_RESPONSE / announce arrives, issue #42).
     * No-op if the row doesn't exist (unfavourited, unseen hubs aren't
     * tracked).
     */
    @Query("UPDATE discovered_hubs SET name = :name WHERE hexHash = :hexHash")
    suspend fun renameHub(hexHash: String, name: String)

    @Query("DELETE FROM discovered_hubs WHERE hexHash = :hexHash")
    suspend fun delete(hexHash: String)
}
