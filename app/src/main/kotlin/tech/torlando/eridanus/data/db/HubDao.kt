package tech.torlando.eridanus.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
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

    @Query("UPDATE discovered_hubs SET starred = NOT starred WHERE hexHash = :hexHash")
    suspend fun toggleStarred(hexHash: String)
}
