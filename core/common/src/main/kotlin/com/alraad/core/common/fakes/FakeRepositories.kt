package com.alraad.core.common.fakes

import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.Bookmark
import com.alraad.core.common.contracts.BookmarksRepository
import com.alraad.core.common.contracts.HistoryEntry
import com.alraad.core.common.contracts.HistoryRepository
import com.alraad.core.common.contracts.SavedStream
import com.alraad.core.common.contracts.SavedStreamsRepository
import com.alraad.core.common.contracts.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [HistoryRepository] for tests and development. */
class FakeHistoryRepository : HistoryRepository {
    private val items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private var nextId = 1L

    override fun observeHistory(): Flow<List<HistoryEntry>> = items

    /** Snapshot for assertions in tests. */
    fun currentEntries(): List<HistoryEntry> = items.value

    override suspend fun add(entry: HistoryEntry): Long {
        val id = nextId++
        items.update { listOf(entry.copy(id = id)) + it }
        return id
    }

    override suspend fun delete(id: Long) {
        items.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun clear() {
        items.value = emptyList()
    }
}

/** In-memory [BookmarksRepository] for tests and development. */
class FakeBookmarksRepository : BookmarksRepository {
    private val items = MutableStateFlow<List<Bookmark>>(emptyList())
    private var nextId = 1L

    override fun observeBookmarks(): Flow<List<Bookmark>> = items

    /** Snapshot for assertions in tests. */
    fun currentBookmarks(): List<Bookmark> = items.value

    override suspend fun add(bookmark: Bookmark): Long {
        val id = nextId++
        items.update { it + bookmark.copy(id = id) }
        return id
    }

    override suspend fun delete(id: Long) {
        items.update { list -> list.filterNot { it.id == id } }
    }
}

/** In-memory [SavedStreamsRepository] for tests and development. Preserves full stream headers. */
class FakeSavedStreamsRepository : SavedStreamsRepository {
    private val items = MutableStateFlow<List<SavedStream>>(emptyList())
    private var nextId = 1L

    override fun observeStreams(): Flow<List<SavedStream>> = items

    /** Snapshot for assertions in tests. */
    fun currentList(): List<SavedStream> = items.value

    override suspend fun getById(id: Long): SavedStream? = items.value.firstOrNull { it.id == id }

    override suspend fun save(stream: SavedStream): Long {
        val id = if (stream.id != 0L) stream.id else nextId++
        val stored = stream.copy(id = id)
        items.update { list -> list.filterNot { it.id == id } + stored }
        return id
    }

    override suspend fun delete(id: Long) {
        items.update { list -> list.filterNot { it.id == id } }
    }
}

/** In-memory [SettingsRepository] for tests and development. */
class FakeSettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun observeSettings(): Flow<AppSettings> = settings

    override suspend fun update(settings: AppSettings) {
        this.settings.value = settings
    }
}
