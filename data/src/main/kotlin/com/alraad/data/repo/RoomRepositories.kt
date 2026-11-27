package com.alraad.data.repo

import com.alraad.core.common.contracts.Bookmark
import com.alraad.core.common.contracts.BookmarksRepository
import com.alraad.core.common.contracts.HistoryEntry
import com.alraad.core.common.contracts.HistoryRepository
import com.alraad.core.common.contracts.SavedStream
import com.alraad.core.common.contracts.SavedStreamsRepository
import com.alraad.data.db.BookmarkDao
import com.alraad.data.db.HistoryDao
import com.alraad.data.db.SavedStreamDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class RoomHistoryRepository
    @Inject
    constructor(
        private val dao: HistoryDao,
    ) : HistoryRepository {
        override fun observeHistory(): Flow<List<HistoryEntry>> = dao.observeAll().map { list -> list.map { it.toContract() } }

        override suspend fun add(entry: HistoryEntry): Long = dao.insert(entry.toEntity())

        override suspend fun delete(id: Long) = dao.deleteById(id)

        override suspend fun clear() = dao.clear()
    }

@Singleton
class RoomBookmarksRepository
    @Inject
    constructor(
        private val dao: BookmarkDao,
    ) : BookmarksRepository {
        override fun observeBookmarks(): Flow<List<Bookmark>> = dao.observeAll().map { list -> list.map { it.toContract() } }

        override suspend fun add(bookmark: Bookmark): Long = dao.insert(bookmark.toEntity())

        override suspend fun delete(id: Long) = dao.deleteById(id)
    }

@Singleton
class RoomSavedStreamsRepository
    @Inject
    constructor(
        private val dao: SavedStreamDao,
    ) : SavedStreamsRepository {
        override fun observeStreams(): Flow<List<SavedStream>> = dao.observeAll().map { list -> list.map { it.toContract() } }

        override suspend fun getById(id: Long): SavedStream? = dao.getById(id)?.toContract()

        override suspend fun save(stream: SavedStream): Long = dao.upsert(stream.toEntity())

        override suspend fun rename(id: Long, title: String) = dao.rename(id, title)

        override suspend fun delete(id: Long) = dao.deleteById(id)
    }
