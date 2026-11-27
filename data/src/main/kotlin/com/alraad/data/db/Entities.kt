package com.alraad.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long,
)

/**
 * Saved stream with the full captured headers preserved as raw JSON
 * (headersJson) so playback later sends exactly what the sniffer saw.
 */
@Entity(tableName = "saved_streams")
data class SavedStreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val format: String,
    val source: String,
    val sizeBytes: Long?,
    val headersJson: String,
    val thumbnailUrl: String?,
    val sourcePageUrl: String?,
    val createdAt: Long,
)

@Entity(tableName = "diagnostics_log")
data class DiagnosticsLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val category: String,
    val level: String,
    val message: String,
    val detailsJson: String,
)
