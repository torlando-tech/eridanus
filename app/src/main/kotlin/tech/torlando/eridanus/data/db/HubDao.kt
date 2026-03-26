package tech.torlando.eridanus.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HubDao {
    @Query("SELECT * FROM discovered_hubs ORDER BY starred DESC, lastSeen DESC")
    fun observeAll(): Flow<List<HubEntity>>

    @Upsert
    suspend fun insert(hub: HubEntity)

    @Query("SELECT * FROM discovered_hubs WHERE hexHash = :hexHash")
    suspend fun getByHash(hexHash: String): HubEntity?

    @Query("UPDATE discovered_hubs SET name = :name, lastSeen = :lastSeen WHERE hexHash = :hexHash")
    suspend fun updateNameAndLastSeen(hexHash: String, name: String, lastSeen: Long)

    @Query("UPDATE discovered_hubs SET starred = NOT starred WHERE hexHash = :hexHash")
    suspend fun toggleStarred(hexHash: String)
}
