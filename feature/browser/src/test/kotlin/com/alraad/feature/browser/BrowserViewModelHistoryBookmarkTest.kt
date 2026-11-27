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
    private lateinit var tabManager: TabManager
    private lateinit var viewModel: BrowserViewModel

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val logger = FakeDiagnosticsLogger()
        history = FakeHistoryRepository()
        bookmarks = FakeBookmarksRepository()
        tabManager = TabManager()
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
    fun `page finish updates its original tab after switching to another tab`() {
        val tabA = tabManager.openTab("https://a.example/old")
        val tabB = tabManager.openTab("https://b.example/")
        tabManager.switchTo(tabB.id)

        viewModel.recordPageFinished(tabA.id, "https://a.example/new", "A finished")

        val tabs = tabManager.state.value
        assertEquals(tabB.id, tabs.activeTabId)
        assertEquals("https://a.example/new", tabs.tabs.first { it.id == tabA.id }.url)
        assertEquals("A finished", tabs.tabs.first { it.id == tabA.id }.title)
        assertEquals("https://b.example/", tabs.tabs.first { it.id == tabB.id }.url)
        assertEquals("", tabs.tabs.first { it.id == tabB.id }.title)
    }

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
