package tech.torlando.eridanus.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HubEntity::class], version = 1, exportSchema = false)
abstract class EridanusDatabase : RoomDatabase() {
    abstract fun hubDao(): HubDao

    companion object {
        @Volatile
        private var instance: EridanusDatabase? = null

        fun getInstance(context: Context): EridanusDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EridanusDatabase::class.java,
                    "eridanus.db"
                ).build().also { instance = it }
            }
    }
}
