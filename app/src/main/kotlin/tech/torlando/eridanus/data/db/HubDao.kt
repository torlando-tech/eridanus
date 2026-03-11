package tech.torlando.eridanus.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HubDao {
    @Query("SELECT * FROM discovered_hubs ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<HubEntity>>

    @Upsert
    suspend fun upsert(hub: HubEntity)
}
