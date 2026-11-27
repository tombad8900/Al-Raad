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
    version = 1,
    exportSchema = false,
)
abstract class AlRaadDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun savedStreamDao(): SavedStreamDao

    abstract fun diagnosticsLogDao(): DiagnosticsLogDao

    companion object {
        const val NAME = "alraad.db"
    }
}
