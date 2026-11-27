package com.alraad.feature.library.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alraad.core.common.contracts.Bookmark
import com.alraad.core.common.contracts.BookmarksRepository
import com.alraad.core.common.contracts.HistoryEntry
import com.alraad.core.common.contracts.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** History + Bookmarks state (Issue #9, part B). */
@HiltViewModel
class HistoryBookmarksViewModel
    @Inject
    constructor(
        private val history: HistoryRepository,
        private val bookmarks: BookmarksRepository,
    ) : ViewModel() {
        val historyEntries: StateFlow<List<HistoryEntry>> =
            history.observeHistory()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val bookmarkEntries: StateFlow<List<Bookmark>> =
            bookmarks.observeBookmarks()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        fun deleteHistory(id: Long) {
            viewModelScope.launch { history.delete(id) }
        }

        fun clearHistory() {
            viewModelScope.launch { history.clear() }
        }

        fun deleteBookmark(id: Long) {
            viewModelScope.launch { bookmarks.delete(id) }
        }
    }
