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
        private val pendingSubtitlesByTab =
            kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<SubtitleTrack>>>(emptyMap())
        private val subtitleAssociationLock = Any()
        val detectedSubtitles: StateFlow<List<SubtitleTrack>> =
            kotlinx.coroutines.flow.combine(tabManager.state, subtitlesByTab) { tabState, byTab ->
                tabState.activeTabId?.let { byTab[it] }.orEmpty()
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val subtitleListener =
            SubtitleSniffer.Listener { track ->
                synchronized(subtitleAssociationLock) {
                    val knownTab = tabManager.state.value.tabs.any { it.id == track.sourceTabId }
                    if (!knownTab) return@synchronized

                    val onCurrentPage = track.sourcePageUrl in currentPageUrlsByTab.value[track.sourceTabId].orEmpty()
                    if (onCurrentPage) {
                        subtitlesByTab.update { current ->
                            current +
                                (track.sourceTabId to
                                    mergeSubtitleTracks(current[track.sourceTabId].orEmpty(), listOf(track)))
                        }
                        pendingSubtitlesByTab.update { current ->
                            val remaining = current[track.sourceTabId].orEmpty().filterNot { it.id == track.id }
                            if (remaining.isEmpty()) {
                                current - track.sourceTabId
                            } else {
                                current + (track.sourceTabId to remaining)
                            }
                        }
                    } else {
                        // WebView's shouldInterceptRequest can race the UI-thread page-start
                        // callback. Hold the page-scoped track briefly instead of dropping it.
                        pendingSubtitlesByTab.update { current ->
                            current + (track.sourceTabId to mergeSubtitleTracks(current[track.sourceTabId].orEmpty(), listOf(track)))
                        }
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
                    synchronized(subtitleAssociationLock) {
                        streamsByTab.update { current -> current.filterKeys { it in liveTabIds } }
                        currentPageUrlsByTab.update { current -> current.filterKeys { it in liveTabIds } }
                        subtitlesByTab.update { current -> current.filterKeys { it in liveTabIds } }
                        pendingSubtitlesByTab.update { current -> current.filterKeys { it in liveTabIds } }
                    }
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
            synchronized(subtitleAssociationLock) {
                if (tabManager.state.value.tabs.none { it.id == tabId }) return@synchronized
                val urls = if (startsNewNavigation) emptySet() else currentPageUrlsByTab.value[tabId].orEmpty()
                val updatedUrls = (urls + url).toList().takeLast(MAX_PAGE_URLS_PER_TAB).toSet()
                currentPageUrlsByTab.update { current -> current + (tabId to updatedUrls) }

                if (startsNewNavigation) {
                    streamsByTab.update { current -> current + (tabId to emptyList()) }
                    subtitlesByTab.update { current -> current + (tabId to emptyList()) }
                }

                val pending = pendingSubtitlesByTab.value[tabId].orEmpty()
                val ready = pending.filter { it.sourcePageUrl in updatedUrls }
                val remaining =
                    if (startsNewNavigation) emptyList() else pending.filterNot { it.sourcePageUrl in updatedUrls }
                if (ready.isNotEmpty()) {
                    subtitlesByTab.update { current ->
                        current + (tabId to mergeSubtitleTracks(current[tabId].orEmpty(), ready))
                    }
                }
                pendingSubtitlesByTab.update { current ->
                    if (remaining.isEmpty()) current - tabId else current + (tabId to remaining)
                }
            }
        }

        private fun mergeSubtitleTracks(
            existing: List<SubtitleTrack>,
            incoming: List<SubtitleTrack>,
        ): List<SubtitleTrack> {
            val merged = existing.toMutableList()
            incoming.forEach { track ->
                val index = merged.indexOfFirst { it.id == track.id }
                if (index >= 0) merged[index] = track else merged += track
            }
            return merged.takeLast(MAX_SUBTITLES_PER_TAB)
        }

        /**
         * Starts playback of a selected stream with its captured request headers and page
         * subtitles. Every stream detected on the same page rides along as a manually
         * switchable source (T5); the picked item starts selected.
         */
        fun playStream(item: DetectedStreamItem) {
            val pageStreams =
                sortDetectedStreamItems(
                    streamsByTab.value[item.sourceTabId].orEmpty().filter {
                        it.sourcePageUrl == item.sourcePageUrl
                    },
                )
            val selectedIndex = pageStreams.indexOfFirst { it.stream.url == item.stream.url }
            val sources = pageStreams.map { it.stream }.ifEmpty { listOf(item.stream) }
            val pageSubtitles =
                subtitlesByTab.value[item.sourceTabId].orEmpty().filter {
                    it.sourceTabId == item.sourceTabId && it.sourcePageUrl == item.sourcePageUrl
                }
            streamActions.play(
                PlaybackRequest(
                    sources = sources,
                    selectedSourceIndex = if (selectedIndex >= 0) selectedIndex else 0,
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
