package com.alraad.core.common.contracts

import kotlinx.coroutines.flow.Flow

/**
 * Frozen repository contracts (Issue #1).
 */

data class HistoryEntry(
    val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

data class Bookmark(
    val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long,
)

data class AppSettings(
    val adBlockEnabled: Boolean = true,
    val javaScriptEnabled: Boolean = true,
    val mixedContentAllowed: Boolean = false,
    val homepageUrl: String = "about:blank",
)

interface HistoryRepository {
    fun observeHistory(): Flow<List<HistoryEntry>>

    suspend fun add(entry: HistoryEntry): Long

    suspend fun delete(id: Long)

    suspend fun clear()
}

interface BookmarksRepository {
    fun observeBookmarks(): Flow<List<Bookmark>>

    suspend fun add(bookmark: Bookmark): Long

    suspend fun delete(id: Long)
}

interface SavedStreamsRepository {
    fun observeStreams(): Flow<List<SavedStream>>

    suspend fun getById(id: Long): SavedStream?

    suspend fun save(stream: SavedStream): Long

    suspend fun rename(id: Long, title: String)

    suspend fun delete(id: Long)
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>

    suspend fun update(settings: AppSettings)
}
