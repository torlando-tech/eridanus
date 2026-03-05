package tech.torlando.ara.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HubEntity::class], version = 1, exportSchema = false)
abstract class AraDatabase : RoomDatabase() {
    abstract fun hubDao(): HubDao

    companion object {
        @Volatile
        private var instance: AraDatabase? = null

        fun getInstance(context: Context): AraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AraDatabase::class.java,
                    "ara.db"
                ).build().also { instance = it }
            }
    }
}
