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
import com.alraad.feature.browser.sniffer.SubtitleSniffer
import com.alraad.feature.browser.actions.StreamActions
import com.alraad.feature.browser.sniffer.StreamSniffer
import com.alraad.feature.browser.tabs.TabManager
import com.alraad.feature.browser.ui.BrowserViewModel
import com.alraad.feature.browser.ui.DetectedStreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Stream actions preserve request headers, the chosen title, and the source page. */
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
        playerController = FakePlayerController()
        subtitleSniffer = SubtitleSniffer()
        val streamActions =
            StreamActions(
                org.robolectric.RuntimeEnvironment.getApplication(),
                playerController,
                savedStreams,
                logger,
            )
        sniffer = StreamSniffer(logger)
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
    fun `save preserves selected title headers and original source page`() {
        val page = "https://page.example/watch"
        val tab = tabManager.openTab(page)
        viewModel.recordVisit(page, "Page A", tab.id)
        val stream =
            StreamUrl(
                url = "https://cdn.example/movie.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
                headers = mapOf("Referer" to "https://page.example/"),
            )
        sniffer.onRequestObserved(stream.url, stream.headers, pageUrl = page, tabId = tab.id)
        viewModel.saveStream(viewModel.detectedStreams.value.single(), "My saved stream")

        val saved = savedStreams.currentList().single()
        assertEquals(stream.headers, saved.stream.headers)
        assertEquals(page, saved.sourcePageUrl)
        assertEquals("My saved stream", saved.title)
    }

    @Test
    fun `detected links remain isolated to the originating tab and page`() {
        val pageA = "https://page-a.example/watch"
        val pageB = "https://page-b.example/watch"
        val tabA = tabManager.openTab(pageA)
        viewModel.recordVisit(pageA, "Page A", tabA.id)
        val tabB = tabManager.openTab(pageB)
        viewModel.recordVisit(pageB, "Page B", tabB.id)

        sniffer.onRequestObserved(
            "https://cdn.example/a.m3u8",
            pageUrl = pageA,
            tabId = tabA.id,
        )
        assertEquals(emptyList<DetectedStreamItem>(), viewModel.detectedStreams.value)

        sniffer.onRequestObserved(
            "https://cdn.example/b.m3u8",
            pageUrl = pageB,
            tabId = tabB.id,
        )
        assertEquals(listOf("https://cdn.example/b.m3u8"), viewModel.detectedStreams.value.map { it.stream.url })

        tabManager.switchTo(tabA.id)
        assertEquals(listOf("https://cdn.example/a.m3u8"), viewModel.detectedStreams.value.map { it.stream.url })
        assertEquals(pageA, viewModel.detectedStreams.value.single().sourcePageUrl)
    }

    @Test
    fun `play request contains only subtitles from the same page and tab with safe headers`() {
        val page = "https://page.example/watch"
        val tab = tabManager.openTab(page)
        viewModel.recordVisit(page, "Page", tab.id)
        subtitleSniffer.onRequestObserved(
            "https://sub.example/captions.ar.vtt",
            mapOf("Cookie" to "secret", "Authorization" to "Bearer secret", "Accept-Language" to "ar"),
            page,
            tab.id,
        )
        sniffer.onRequestObserved(
            "https://cdn.example/movie.m3u8",
            pageUrl = page,
            tabId = tab.id,
        )

        viewModel.playStream(viewModel.detectedStreams.value.single())

        val request = playerController.playedRequests.single()
        assertEquals(1, request.subtitles.size)
        assertEquals(page, request.subtitles.single().sourcePageUrl)
        assertEquals(tab.id, request.subtitles.single().sourceTabId)
        assertEquals("ar", request.subtitles.single().language)
        assertEquals(mapOf("Accept-Language" to "ar"), request.subtitles.single().requestHeaders)
    }

}
