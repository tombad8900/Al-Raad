package com.alraad.feature.browser

import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.fakes.FakeAdBlocker
import com.alraad.core.common.fakes.FakeBookmarksRepository
import com.alraad.core.common.fakes.FakeDiagnosticsLogger
import com.alraad.core.common.fakes.FakeHistoryRepository
import com.alraad.core.common.fakes.FakePlayerController
import com.alraad.core.common.fakes.FakeSavedStreamsRepository
import com.alraad.core.common.fakes.FakeSettingsRepository
import com.alraad.feature.browser.actions.StreamActions
import com.alraad.feature.browser.sniffer.StreamSniffer
import com.alraad.feature.browser.sniffer.SubtitleSniffer
import com.alraad.feature.browser.tabs.TabManager
import com.alraad.feature.browser.ui.BrowserViewModel
import com.alraad.feature.browser.ui.DetectedStreamItem
import com.alraad.feature.browser.ui.sortDetectedStreamItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Current-page stream capture, selection, and save metadata regressions. */
@RunWith(RobolectricTestRunner::class)
class BrowserViewModelStreamActionsTest {
    private lateinit var savedStreams: FakeSavedStreamsRepository
    private lateinit var tabManager: TabManager
    private lateinit var viewModel: BrowserViewModel
    private lateinit var sniffer: StreamSniffer
    private lateinit var subtitleSniffer: SubtitleSniffer
    private lateinit var playerController: FakePlayerController

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val logger = FakeDiagnosticsLogger()
        savedStreams = FakeSavedStreamsRepository()
        tabManager = TabManager()
        sniffer = StreamSniffer(logger)
        subtitleSniffer = SubtitleSniffer()
        playerController = FakePlayerController()
        val streamActions =
            StreamActions(
                org.robolectric.RuntimeEnvironment.getApplication(),
                playerController,
                savedStreams,
                logger,
            )
        viewModel =
            BrowserViewModel(
                tabManager = tabManager,
                webViewIntegration =
                    WebViewIntegrationHelper(
                        FakeAdBlocker(),
                        logger,
                        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    ),
                diagnosticsLogger = logger,
                sniffer = sniffer,
                subtitleSniffer = subtitleSniffer,
                streamActions = streamActions,
                settingsRepository = FakeSettingsRepository(),
                historyRepository = FakeHistoryRepository(),
                bookmarksRepository = FakeBookmarksRepository(),
            )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `save preserves selected title headers and originating page`() {
        val page = "https://page.example/watch"
        val tab = tabManager.openTab(page)
        viewModel.recordPageStarted(tab.id, page, startsNewNavigation = true)
        val stream =
            StreamUrl(
                url = "https://cdn.example/movie.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
                headers = mapOf("Referer" to "https://page.example/"),
            )
        sniffer.onRequestObserved(stream.url, stream.headers, pageUrl = page, tabId = tab.id)
        val item = viewModel.detectedStreams.value.single()

        viewModel.saveStream(item, "My saved stream")

        val saved = savedStreams.currentList().single()
        assertEquals(stream.headers, saved.stream.headers)
        assertEquals(page, saved.sourcePageUrl)
        assertEquals("My saved stream", saved.title)
    }

    @Test
    fun `stream links stay isolated by tab and stale pages are ignored`() {
        val pageA = "https://page-a.example/watch"
        val pageB = "https://page-b.example/watch"
        val tabA = tabManager.openTab(pageA)
        viewModel.recordPageStarted(tabA.id, pageA, startsNewNavigation = true)
        val tabB = tabManager.openTab(pageB)
        viewModel.recordPageStarted(tabB.id, pageB, startsNewNavigation = true)

        sniffer.onRequestObserved("https://cdn.example/a.m3u8", pageUrl = pageA, tabId = tabA.id)
        assertTrue(viewModel.detectedStreams.value.isEmpty())

        sniffer.onRequestObserved("https://cdn.example/b.m3u8", pageUrl = pageB, tabId = tabB.id)
        assertEquals(
            listOf("https://cdn.example/b.m3u8"),
            viewModel.detectedStreams.value.map { it.stream.url },
        )

        tabManager.switchTo(tabA.id)
        assertEquals(
            listOf("https://cdn.example/a.m3u8"),
            viewModel.detectedStreams.value.map { it.stream.url },
        )

        viewModel.recordPageStarted(tabA.id, "https://page-a.example/next", startsNewNavigation = true)
        sniffer.onRequestObserved("https://cdn.example/stale.m3u8", pageUrl = pageA, tabId = tabA.id)
        assertTrue(viewModel.detectedStreams.value.isEmpty())
    }

    @Test
    fun `playback receives only subtitles from the selected page and tab`() {
        val pageA = "https://page-a.example/watch"
        val pageB = "https://page-b.example/watch"
        val tabA = tabManager.openTab(pageA)
        viewModel.recordPageStarted(tabA.id, pageA, startsNewNavigation = true)
        val tabB = tabManager.openTab(pageB)
        viewModel.recordPageStarted(tabB.id, pageB, startsNewNavigation = true)

        subtitleSniffer.onRequestObserved(
            "https://subtitles.example/a.ar.vtt",
            mapOf("Cookie" to "video-session-secret", "Accept" to "text/vtt"),
            pageA,
            tabA.id,
        )
        subtitleSniffer.onRequestObserved(
            "https://subtitles.example/b.en.srt",
            mapOf("Authorization" to "Bearer secret"),
            pageB,
            tabB.id,
        )
        val stream =
            StreamUrl(
                url = "https://cdn.example/a.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
                headers = mapOf("Cookie" to "video-session-secret"),
            )

        viewModel.playStream(DetectedStreamItem(stream, pageA, tabA.id))

        val request = requireNotNull(playerController.lastPlaybackRequest)
        assertEquals(listOf("https://subtitles.example/a.ar.vtt"), request.subtitles.map { it.url })
        assertEquals(pageA, request.sourcePageUrl)
        assertEquals(tabA.id, request.sourceTabId)
        assertFalse(request.subtitles.single().requestHeaders.containsKey("Cookie"))
        assertFalse(request.subtitles.single().requestHeaders.containsKey("Authorization"))
    }

    @Test
    fun `stream picker sorts formats and removes duplicate URLs`() {
        val tab = tabManager.openTab("https://page.example/watch")
        val dash =
            DetectedStreamItem(
                StreamUrl("https://cdn.example/z.mpd", StreamFormat.DASH, StreamSource.NETWORK),
                tab.url,
                tab.id,
            )
        val hls =
            DetectedStreamItem(
                StreamUrl("https://cdn.example/a.m3u8", StreamFormat.HLS, StreamSource.NETWORK),
                tab.url,
                tab.id,
            )
        val duplicate = hls.copy(stream = hls.stream.copy(headers = mapOf("Range" to "bytes=0-")))

        val sorted = sortDetectedStreamItems(listOf(hls, dash, duplicate))

        assertEquals(listOf(StreamFormat.DASH, StreamFormat.HLS), sorted.map { it.stream.format })
        assertEquals(2, sorted.size)
    }
}
