package com.alraad.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        HistoryEntity::class,
        BookmarkEntity::class,
        SavedStreamEntity::class,
        DiagnosticsLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AlRaadDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun savedStreamDao(): SavedStreamDao

    abstract fun diagnosticsLogDao(): DiagnosticsLogDao

    companion object {
        const val NAME = "alraad.db"

        /** Adds DRM license capture columns to saved_streams. */
        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE saved_streams ADD COLUMN drmLicenseUrl TEXT")
                    db.execSQL("ALTER TABLE saved_streams ADD COLUMN drmHeadersJson TEXT NOT NULL DEFAULT ''")
                }
            }
    }
}
