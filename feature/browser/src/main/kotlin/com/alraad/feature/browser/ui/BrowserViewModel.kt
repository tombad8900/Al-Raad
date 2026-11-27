package com.alraad.feature.browser.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.Bookmark
import com.alraad.core.common.contracts.BookmarksRepository
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.HistoryEntry
import com.alraad.core.common.contracts.HistoryRepository
import com.alraad.core.common.contracts.SettingsRepository
import com.alraad.core.common.contracts.StreamListener
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.actions.StreamActions
import com.alraad.feature.browser.tabs.TabManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("LongParameterList") // Hilt-injected collaborators; grouping would obscure the graph
@HiltViewModel
class BrowserViewModel
    @Inject
    constructor(
        val tabManager: TabManager,
        val webViewIntegration: WebViewIntegrationHelper,
        val diagnosticsLogger: DiagnosticsLogger,
        val sniffer: com.alraad.feature.browser.sniffer.StreamSniffer,
        private val streamActions: StreamActions,
        settingsRepository: SettingsRepository,
        private val historyRepository: HistoryRepository,
        private val bookmarksRepository: BookmarksRepository,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings> =
            settingsRepository.observeSettings()
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

        /** Streams detected by the sniffer, newest first; updates replace by URL. */
        private val streams = kotlinx.coroutines.flow.MutableStateFlow<List<StreamUrl>>(emptyList())
        val detectedStreams: StateFlow<List<StreamUrl>> = streams

        private val streamListener =
            object : StreamListener {
                override fun onStreamDetected(stream: StreamUrl) {
                    streams.value = listOf(stream) + streams.value
                }

                override fun onStreamUpdated(stream: StreamUrl) {
                    streams.value = streams.value.map { if (it.url == stream.url) stream else it }
                }
            }

        init {
            sniffer.registerListener(streamListener)
        }

        // public override so lifecycle coverage can invoke it directly from tests
        public override fun onCleared() {
            sniffer.unregisterListener(streamListener)
            super.onCleared()
        }

        /** Starts playback of a detected stream; the PLAYER route renders it via currentStream. */
        fun playStream(stream: StreamUrl) = streamActions.play(stream)

        /** Last page reported by the WebView as finished loading; basis for bookmarking. */
        var lastVisited: Pair<String, String>? = null
            private set

        /** Records a finished page load into history. Skips empty and internal pages. */
        fun recordVisit(
            url: String,
            title: String,
        ) {
            if (url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
            lastVisited = url to title
            viewModelScope.launch {
                historyRepository.add(
                    HistoryEntry(
                        url = url,
                        title = title.ifBlank { url },
                        visitedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        /** Bookmarks the last visited page; no-op when nothing has loaded yet. */
        fun bookmarkLastVisited() {
            val (url, title) = lastVisited ?: return
            viewModelScope.launch {
                bookmarksRepository.add(
                    Bookmark(
                        url = url,
                        title = title.ifBlank { url },
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
