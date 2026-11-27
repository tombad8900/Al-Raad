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
import com.alraad.core.common.contracts.StreamListener
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.contracts.SubtitleTrack
import com.alraad.feature.browser.actions.StreamActions
import com.alraad.feature.browser.tabs.TabManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_STREAMS_PER_TAB = 100
private const val MAX_SUBTITLES_PER_TAB = 100

@Suppress("LongParameterList") // Hilt-injected collaborators; grouping would obscure the graph
@HiltViewModel
class BrowserViewModel
    @Inject
    constructor(
        val tabManager: TabManager,
        val webViewIntegration: WebViewIntegrationHelper,
        val diagnosticsLogger: DiagnosticsLogger,
        val sniffer: StreamSniffer,
        val subtitleSniffer: SubtitleSniffer,
        private val streamActions: StreamActions,
        settingsRepository: SettingsRepository,
        private val historyRepository: HistoryRepository,
        private val bookmarksRepository: BookmarksRepository,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings> =
            settingsRepository.observeSettings()
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

        /** Streams are retained per tab and exposed only for that tab's current page. */
        private val streamsByTab =
            kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<DetectedStreamItem>>>(emptyMap())
        val detectedStreams: StateFlow<List<DetectedStreamItem>> =
            combine(tabManager.state, streamsByTab) { tabState, byTab ->
                val activeId = tabState.activeTabId
                val activeUrl = tabState.tabs.firstOrNull { it.id == activeId }?.url
                activeId?.let { byTab[it] }.orEmpty().filter { activeUrl == null || it.sourcePageUrl == activeUrl }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val subtitlesByTab =
            kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<SubtitleTrack>>>(emptyMap())
        val detectedSubtitles: StateFlow<List<SubtitleTrack>> =
            combine(tabManager.state, subtitlesByTab) { tabState, byTab ->
                val activeId = tabState.activeTabId
                val activeUrl = tabState.tabs.firstOrNull { it.id == activeId }?.url
                activeId?.let { byTab[it] }.orEmpty().filter { activeUrl == null || it.sourcePageUrl == activeUrl }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val subtitleListener =
            SubtitleSniffer.Listener { track ->
                subtitlesByTab.update { current ->
                    val liveTabIds = tabManager.state.value.tabs.mapTo(mutableSetOf()) { it.id }
                    val retained = current.filterKeys { it in liveTabIds }
                    val existing = retained[track.sourceTabId].orEmpty()
                    val index = existing.indexOfFirst { it.id == track.id }
                    val updated =
                        if (index >= 0) existing.toMutableList().apply { set(index, track) } else existing + track
                    retained + (track.sourceTabId to updated.takeLast(MAX_SUBTITLES_PER_TAB))
                }
            }

        private val streamListener =
            object : StreamListener {
                override fun onStreamDetected(stream: StreamUrl) = Unit

                override fun onStreamUpdated(stream: StreamUrl) = Unit

                override fun onStreamObserved(
                    stream: StreamUrl,
                    pageUrl: String?,
                    tabId: String?,
                ) {
                    val tabState = tabManager.state.value
                    val sourcePage = pageUrl ?: return
                    val resolvedTabId =
                        tabId ?: tabState.tabs.firstOrNull { it.url == sourcePage }?.id
                            ?: tabState.activeTabId
                            ?: return
                    if (tabState.tabs.none { it.id == resolvedTabId }) return
                    val item = DetectedStreamItem(stream, sourcePage, resolvedTabId)
                    streamsByTab.update { current ->
                        val liveTabIds = tabState.tabs.mapTo(mutableSetOf()) { it.id }
                        val retained = current.filterKeys { it in liveTabIds }
                        val existing = retained[resolvedTabId].orEmpty()
                        val index = existing.indexOfFirst { it.stream.url == stream.url }
                        val updated =
                            if (index >= 0) existing.toMutableList().apply { set(index, item) } else existing + item
                        retained + (resolvedTabId to updated.takeLast(MAX_STREAMS_PER_TAB))
                    }
                }
            }

        init {
            sniffer.registerListener(streamListener)
            subtitleSniffer.registerListener(subtitleListener)
        }

        // public override so lifecycle coverage can invoke it directly from tests
        public override fun onCleared() {
            sniffer.unregisterListener(streamListener)
            subtitleSniffer.unregisterListener(subtitleListener)
            super.onCleared()
        }

        /** Starts the chosen source with every subtitle captured from the same page and tab. */
        fun playStream(item: DetectedStreamItem) {
            val pageSources =
                streamsByTab.value[item.sourceTabId].orEmpty()
                    .filter { it.sourcePageUrl == item.sourcePageUrl }
                    .map { it.stream }
            val sources = pageSources.ifEmpty { listOf(item.stream) }
            val selectedIndex = sources.indexOfFirst { it.url == item.stream.url }.coerceAtLeast(0)
            val subtitles =
                subtitlesByTab.value[item.sourceTabId].orEmpty()
                    .filter { it.sourcePageUrl == item.sourcePageUrl }
            streamActions.play(
                PlaybackRequest(
                    sources = sources,
                    selectedSourceIndex = selectedIndex,
                    subtitles = subtitles,
                    sourcePageUrl = item.sourcePageUrl,
                    sourceTabId = item.sourceTabId,
                ),
            )
        }

        /** Last page reported by the WebView as finished loading; basis for bookmarking. */
        var lastVisited: Pair<String, String>? = null
            private set

        /** Records a finished page load into history. Skips empty and internal pages. */
        fun recordVisit(
            url: String,
            title: String,
            tabId: String? = null,
        ) {
            if (url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
            val resolvedTabId = tabId ?: tabManager.state.value.activeTabId
            if (resolvedTabId != null) {
                val previousUrl = tabManager.state.value.tabs.firstOrNull { it.id == resolvedTabId }?.url
                tabManager.updateTab(resolvedTabId, url = url, title = title)
                if (previousUrl != null && previousUrl != url) {
                    streamsByTab.update { current ->
                        val existing = current[resolvedTabId].orEmpty()
                        current + (
                            resolvedTabId to existing.map {
                                if (it.sourcePageUrl == previousUrl) it.copy(sourcePageUrl = url) else it
                            }
                        )
                    }
                    subtitlesByTab.update { current ->
                        val existing = current[resolvedTabId].orEmpty()
                        current + (
                            resolvedTabId to existing.map {
                                if (it.sourcePageUrl == previousUrl) it.copy(sourcePageUrl = url) else it
                            }
                        )
                    }
                }
            }
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

        /** Saves the selected link with its user-provided title and original page URL. */
        fun saveStream(
            item: DetectedStreamItem,
            title: String,
        ) {
            viewModelScope.launch {
                streamActions.saveToLibrary(item.stream, title = title, sourcePageUrl = item.sourcePageUrl)
            }
        }
    }
