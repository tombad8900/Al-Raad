package com.alraad.feature.library.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alraad.core.common.contracts.SavedStream
import com.alraad.core.common.contracts.SavedStreamsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Saved Streams library state (Issue #9, part A). */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val savedStreams: SavedStreamsRepository,
    ) : ViewModel() {
        val streams: StateFlow<List<SavedStream>> =
            savedStreams.observeStreams()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        fun delete(id: Long) {
            viewModelScope.launch { savedStreams.delete(id) }
        }
    }
