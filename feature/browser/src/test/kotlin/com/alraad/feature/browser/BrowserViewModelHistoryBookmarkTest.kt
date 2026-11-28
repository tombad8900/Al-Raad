package com.alraad.feature.browser

import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Finished page loads must land in history; bookmarking uses the last visited page. */
@RunWith(RobolectricTestRunner::class)
class BrowserViewModelHistoryBookmarkTest {
    private lateinit var history: FakeHistoryRepository
    private lateinit var bookmarks: FakeBookmarksRepository
    private lateinit var viewModel: BrowserViewModel

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val logger = FakeDiagnosticsLogger()
        history = FakeHistoryRepository()
        bookmarks = FakeBookmarksRepository()
        viewModel =
            BrowserViewModel(
                tabManager = TabManager(),
                webViewIntegration =
                    WebViewIntegrationHelper(
                        FakeAdBlocker(),
                        logger,
                        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    ),
                diagnosticsLogger = logger,
                sniffer = StreamSniffer(logger),
                subtitleSniffer = SubtitleSniffer(),
                streamActions =
                    StreamActions(
                        org.robolectric.RuntimeEnvironment.getApplication(),
                        FakePlayerController(),
                        FakeSavedStreamsRepository(),
                        logger,
                    ),
                settingsRepository = FakeSettingsRepository(),
                historyRepository = history,
                bookmarksRepository = bookmarks,
            )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `finished page load is recorded in history`() {
        viewModel.recordVisit("https://example.com/", "Example Domain")
        val entries = history.currentEntries()
        assertEquals(1, entries.size)
        assertEquals("https://example.com/", entries[0].url)
        assertEquals("Example Domain", entries[0].title)
    }

    @Test
    fun `blank and internal pages are not recorded`() {
        viewModel.recordVisit("about:blank", "")
        viewModel.recordVisit("", "")
        viewModel.recordVisit("data:text/html,hi", "")
        assertTrue(history.currentEntries().isEmpty())
    }

    @Test
    fun `blank title falls back to the url`() {
        viewModel.recordVisit("https://example.com/", "")
        assertEquals("https://example.com/", history.currentEntries()[0].title)
    }

    @Test
    fun `bookmark uses the last visited page`() {
        viewModel.bookmarkLastVisited()
        assertTrue(bookmarks.currentBookmarks().isEmpty())
        viewModel.recordVisit("https://example.com/", "Example Domain")
        viewModel.bookmarkLastVisited()
        val saved = bookmarks.currentBookmarks()
        assertEquals(1, saved.size)
        assertEquals("https://example.com/", saved[0].url)
    }
}
