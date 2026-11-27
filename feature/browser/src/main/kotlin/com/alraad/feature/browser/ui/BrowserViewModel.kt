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
import com.alraad.core.common.contracts.PlaybackRequest
import com.alraad.core.common.contracts.SettingsRepository
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.contracts.SubtitleTrack
import com.alraad.feature.browser.actions.StreamActions
import com.alraad.feature.browser.sniffer.StreamObservationListener
import com.alraad.feature.browser.sniffer.SubtitleSniffer
import com.alraad.feature.browser.tabs.TabManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_STREAMS_PER_TAB = 100
private const val MAX_PAGE_URLS_PER_TAB = 12
private const val MAX_SUBTITLES_PER_TAB = 100

@Suppress("LongParameterList") // Hilt-injected collaborators; grouping would obscure the graph
@HiltViewModel
class BrowserViewModel
    @Inject
    constructor(
        val tabManager: TabManager,
        val webViewIntegration: WebViewIntegrationHelper,
        val diagnosticsLogger: DiagnosticsLogger,
        val sniffer: com.alraad.feature.browser.sniffer.StreamSniffer,
        val subtitleSniffer: SubtitleSniffer,
        private val streamActions: StreamActions,
        settingsRepository: SettingsRepository,
        private val historyRepository: HistoryRepository,
        private val bookmarksRepository: BookmarksRepository,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings> =
            settingsRepository.observeSettings()
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

        /** Current-page streams, isolated by the tab that owns the originating document. */
        private val streamsByTab =
            kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<DetectedStreamItem>>>(emptyMap())
        private val currentPageUrlsByTab =
            kotlinx.coroutines.flow.MutableStateFlow<Map<String, Set<String>>>(emptyMap())
        val detectedStreams: StateFlow<List<DetectedStreamItem>> =
            kotlinx.coroutines.flow.combine(tabManager.state, streamsByTab) { tabState, byTab ->
                tabState.activeTabId?.let { byTab[it] }.orEmpty()
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** Subtitle tracks are isolated by the same source tab/page as detected streams. */
        private val subtitlesByTab =
            kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<SubtitleTrack>>>(emptyMap())
        val detectedSubtitles: StateFlow<List<SubtitleTrack>> =
            kotlinx.coroutines.flow.combine(tabManager.state, subtitlesByTab) { tabState, byTab ->
                tabState.activeTabId?.let { byTab[it] }.orEmpty()
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val subtitleListener =
            SubtitleSniffer.Listener { track ->
                val state = tabManager.state.value
                val knownTab = state.tabs.any { it.id == track.sourceTabId }
                val onCurrentPage = track.sourcePageUrl in currentPageUrlsByTab.value[track.sourceTabId].orEmpty()
                if (knownTab && onCurrentPage) {
                    subtitlesByTab.update { current ->
                        val existing = current[track.sourceTabId].orEmpty()
                        val index = existing.indexOfFirst { it.id == track.id }
                        val updated =
                            if (index >= 0) existing.toMutableList().apply { set(index, track) } else existing + track
                        current + (track.sourceTabId to updated.takeLast(MAX_SUBTITLES_PER_TAB))
                    }
                }
            }

        private val streamObservationListener =
            object : StreamObservationListener {
                override fun onStreamObserved(
                    stream: StreamUrl,
                    pageUrl: String?,
                    tabId: String?,
                ) {
                    if (pageUrl == null || tabId == null) return
                    val sourcePage = pageUrl
                    val sourceTabId = tabId
                    val knownTab = tabManager.state.value.tabs.any { it.id == sourceTabId }
                    val onCurrentPage = sourcePage in currentPageUrlsByTab.value[sourceTabId].orEmpty()
                    if (!knownTab || !onCurrentPage) return
                    val item = DetectedStreamItem(stream, sourcePage, sourceTabId)
                    streamsByTab.update { current ->
                        val existing = current[sourceTabId].orEmpty()
                        val index = existing.indexOfFirst { it.stream.url == stream.url }
                        val updated =
                            if (index >= 0) existing.toMutableList().apply { set(index, item) } else existing + item
                        current + (sourceTabId to updated.takeLast(MAX_STREAMS_PER_TAB))
                    }
                }
            }

        init {
            sniffer.registerObservationListener(streamObservationListener)
            subtitleSniffer.registerListener(subtitleListener)
            viewModelScope.launch {
                tabManager.state.collect { state ->
                    val liveTabIds = state.tabs.mapTo(mutableSetOf()) { it.id }
                    streamsByTab.update { current -> current.filterKeys { it in liveTabIds } }
                    currentPageUrlsByTab.update { current -> current.filterKeys { it in liveTabIds } }
                    subtitlesByTab.update { current -> current.filterKeys { it in liveTabIds } }
                }
            }
        }

        public override fun onCleared() {
            sniffer.unregisterObservationListener(streamObservationListener)
            subtitleSniffer.unregisterListener(subtitleListener)
            super.onCleared()
        }

        /** A new top-level navigation clears stale links; redirect callbacks preserve its chain. */
        fun recordPageStarted(
            tabId: String,
            url: String,
            startsNewNavigation: Boolean,
        ) {
            if (tabManager.state.value.tabs.none { it.id == tabId }) return
            currentPageUrlsByTab.update { current ->
                val urls = if (startsNewNavigation) emptySet() else current[tabId].orEmpty()
                current + (tabId to (urls + url).toList().takeLast(MAX_PAGE_URLS_PER_TAB).toSet())
            }
            if (startsNewNavigation) {
                streamsByTab.update { current -> current + (tabId to emptyList()) }
                subtitlesByTab.update { current -> current + (tabId to emptyList()) }
            }
        }

        /** Starts playback of a selected stream with its captured request headers. */
        fun playStream(item: DetectedStreamItem) {
            val pageSources =
                streamsByTab.value[item.sourceTabId].orEmpty()
                    .filter {
                        it.sourceTabId == item.sourceTabId && it.sourcePageUrl == item.sourcePageUrl
                    }
                    .map { it.stream }
                    .distinctBy { it.url }
                    .toMutableList()
            if (pageSources.none { it.url == item.stream.url }) pageSources += item.stream
            val selectedIndex = pageSources.indexOfFirst { it.url == item.stream.url }.coerceAtLeast(0)
            val pageSubtitles =
                subtitlesByTab.value[item.sourceTabId].orEmpty().filter {
                    it.sourceTabId == item.sourceTabId && it.sourcePageUrl == item.sourcePageUrl
                }
            streamActions.play(
                PlaybackRequest(
                    sources = pageSources,
                    selectedSourceIndex = selectedIndex,
                    subtitles = pageSubtitles,
                    sourcePageUrl = item.sourcePageUrl,
                    sourceTabId = item.sourceTabId,
                ),
            )
        }

        /** Last page reported by the WebView as finished loading; basis for bookmarking. */
        var lastVisited: Pair<String, String>? = null
            private set

        /**
         * Applies a completed WebView navigation to the tab that started it. The active
         * tab may have changed while the asynchronous page-finished callback was pending.
         */
        fun recordPageFinished(
            tabId: String,
            url: String,
            title: String,
        ) {
            if (tabManager.state.value.tabs.none { it.id == tabId }) return
            tabManager.updateTab(tabId, url = url, title = title)
            recordVisit(url, title)
        }

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

        /** Copies the detected stream URL to the clipboard. */
        fun copyStream(item: DetectedStreamItem) = streamActions.copyUrl(item.stream)

        /** Saves the selected stream with the exact request headers and source page. */
        fun saveStream(
            item: DetectedStreamItem,
            requestedTitle: String,
        ) {
            val title = requestedTitle.trim().ifBlank { android.net.Uri.parse(item.stream.url).host ?: item.stream.url }
            viewModelScope.launch {
                streamActions.saveToLibrary(item.stream, title = title, sourcePageUrl = item.sourcePageUrl)
            }
        }
    }
