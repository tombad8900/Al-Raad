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
import com.alraad.feature.browser.tabs.TabManager
import com.alraad.feature.browser.ui.BrowserViewModel
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

/** The stream bar's Save action must persist headers and the active tab as source page. */
@RunWith(RobolectricTestRunner::class)
class BrowserViewModelStreamActionsTest {
    private lateinit var savedStreams: FakeSavedStreamsRepository
    private lateinit var tabManager: TabManager
    private lateinit var viewModel: BrowserViewModel

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val logger = FakeDiagnosticsLogger()
        savedStreams = FakeSavedStreamsRepository()
        tabManager = TabManager()
        val streamActions =
            StreamActions(
                org.robolectric.RuntimeEnvironment.getApplication(),
                FakePlayerController(),
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
                sniffer = StreamSniffer(logger),
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
    fun `save persists headers and the active tab url as source page`() {
        val tab = tabManager.openTab("https://page.example/watch")
        tabManager.updateTab(tab.id, url = "https://page.example/watch")
        val stream =
            StreamUrl(
                url = "https://cdn.example/movie.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
                headers = mapOf("Referer" to "https://page.example/"),
            )
        viewModel.saveStream(stream, "My chosen title")
        val saved = savedStreams.currentList()
        assertEquals(1, saved.size)
        assertEquals(stream.headers, saved[0].stream.headers)
        assertEquals("https://page.example/watch", saved[0].sourcePageUrl)
        assertEquals("My chosen title", saved[0].title)
    }

    @Test
    fun `blank requested title falls back to stream host`() {
        tabManager.openTab("https://page.example/watch")
        val stream =
            StreamUrl(
                url = "https://cdn.example/movie.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
            )

        viewModel.saveStream(stream, "   ")

        assertEquals("cdn.example", savedStreams.currentList().single().title)
    }
}
