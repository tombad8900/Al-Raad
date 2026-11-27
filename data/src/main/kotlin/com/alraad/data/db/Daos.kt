package com.alraad.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SavedStreamDao {
    @Query("SELECT * FROM saved_streams ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SavedStreamEntity>>

    @Query("SELECT * FROM saved_streams WHERE id = :id")
    suspend fun getById(id: Long): SavedStreamEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stream: SavedStreamEntity): Long

    @Query("UPDATE saved_streams SET title = :title WHERE id = :id")
    suspend fun rename(
        id: Long,
        title: String,
    )

    @Query("DELETE FROM saved_streams WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface DiagnosticsLogDao {
    @Query("SELECT * FROM diagnostics_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticsLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DiagnosticsLogEntity): Long

    @Query("SELECT * FROM diagnostics_log ORDER BY timestamp ASC")
    suspend fun allForExport(): List<DiagnosticsLogEntity>

    @Query("DELETE FROM diagnostics_log")
    suspend fun clear()
}
