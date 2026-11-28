package com.alraad.feature.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.feature.browser.sniffer.StreamSniffer
import com.alraad.feature.browser.sniffer.SubtitleSniffer
import com.alraad.feature.browser.tabs.TabManager
import com.alraad.feature.browser.ui.BrowserScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression: launch must open the homepage tab instead of leaving a blank white WebView. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BrowserScreenLaunchTest {
    @get:Rule val composeRule = createComposeRule()
    private val fakeLogger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) = Unit

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText() = ""

            override suspend fun clear() = Unit
        }
    private val fakeAdBlocker =
        object : AdBlocker {
            override suspend fun shouldBlock(url: String) = false

            override fun cosmeticCss(host: String) = ""
        }

    @Test fun `launch opens homepage tab and address bar shows its URL`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val tabManager = TabManager()
        val helper = WebViewIntegrationHelper(fakeAdBlocker, fakeLogger, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val settings = AppSettings(homepageUrl = "https://home.example")
        composeRule.setContent {
            BrowserScreen(
                tabManager,
                helper,
                fakeLogger,
                StreamSniffer(fakeLogger),
                SubtitleSniffer(),
                settings,
            )
        }
        composeRule.waitForIdle()
        assertTrue(tabManager.state.value.tabs.any { it.url == "https://home.example" })
        composeRule.onNodeWithText("https://home.example").assertIsDisplayed()
    }
}
