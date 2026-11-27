package com.alraad.feature.browser

import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.StreamSource
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The ViewModel must unregister its sniffer listener when cleared (no listener leak). */
@RunWith(RobolectricTestRunner::class)
class BrowserViewModelLifecycleTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() = Dispatchers.resetMain()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onCleared unregisters the sniffer listener so old view models stop updating`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val logger = FakeDiagnosticsLogger()
        val sniffer = StreamSniffer(logger)
        val viewModel =
            BrowserViewModel(
                tabManager = TabManager(),
                webViewIntegration =
                    WebViewIntegrationHelper(
                        FakeAdBlocker(),
                        logger,
                        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    ),
                diagnosticsLogger = logger,
                sniffer = sniffer,
                streamActions =
                    StreamActions(
                        org.robolectric.RuntimeEnvironment.getApplication(),
                        FakePlayerController(),
                        FakeSavedStreamsRepository(),
                        logger,
                    ),
                settingsRepository = FakeSettingsRepository(),
                historyRepository = FakeHistoryRepository(),
                bookmarksRepository = FakeBookmarksRepository(),
            )
        viewModel.onCleared()
        sniffer.onRequestObserved(
            "https://cdn.example/movie.m3u8",
            emptyMap(),
            null,
            StreamSource.NETWORK,
            "https://page.example",
        )
        assertTrue("cleared ViewModel still receives stream updates", viewModel.detectedStreams.value.isEmpty())
    }
}
