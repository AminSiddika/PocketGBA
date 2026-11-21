package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RomEntity::class, CheatEntity::class, ShaderProfileEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun emulatorDao(): EmulatorDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE roms ADD COLUMN preferredShaderProfileId INTEGER")
                db.execSQL("ALTER TABLE roms ADD COLUMN preferredSpeedMultiplier REAL")
                db.execSQL("ALTER TABLE cheats ADD COLUMN groupName TEXT NOT NULL DEFAULT 'Manual'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shader_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        baseMode TEXT NOT NULL,
                        gridIntensity REAL NOT NULL,
                        saturation REAL NOT NULL,
                        contrast REAL NOT NULL,
                        brightness REAL NOT NULL,
                        warmth REAL NOT NULL,
                        smoothFilter INTEGER NOT NULL,
                        agslSource TEXT,
                        sourceFileName TEXT,
                        isBuiltIn INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pocket_advance.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
