// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HubEntity::class], version = 2, exportSchema = false)
abstract class EridanusDatabase : RoomDatabase() {
    abstract fun hubDao(): HubDao

    companion object {
        @Volatile
        private var instance: EridanusDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE discovered_hubs ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): EridanusDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EridanusDatabase::class.java,
                    "eridanus.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
