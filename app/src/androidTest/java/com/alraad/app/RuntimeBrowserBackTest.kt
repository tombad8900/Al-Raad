package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.feature.browser.tabs.TabManager
import com.alraad.feature.browser.tabs.TabManagerTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Runtime acceptance for the browser back and tab behavior on a real WebView (emulator, not a phone):
 * back through page history, return to search at the history start, stay at the search screen,
 * keep a tab's WebView and history across a tab switch and a section switch, and leave only through Exit.
 * Pages are served by a local MockWebServer, so no external network is involved.
 */
class RuntimeBrowserBackTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tabManager: TabManager by lazy {
        EntryPointAccessors
            .fromApplication(instrumentation.targetContext.applicationContext, TabManagerTestEntryPoint::class.java)
            .tabManager()
    }

    @Before fun resetTabs() {
        instrumentation.runOnMainSync { tabManager.reset() }
    }

    private fun activeWebView(): WebView? {
        val id = tabManager.state.value.activeTabId ?: return null
        return tabManager.webViewPool(instrumentation.targetContext.applicationContext).get(id)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun url(): String? = onMain { activeWebView()?.url }

    private fun awaitUrl(
        expected: String,
        timeoutMs: Long = 30_000,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (url() == expected) return
            Thread.sleep(250)
        }
        capture("backtest-timeout-${System.currentTimeMillis()}.png")
        assertEquals("WebView url", expected, url())
    }

    private fun back() {
        Espresso.pressBackUnconditionally()
        composeRule.waitForIdle()
    }

    private fun typeAddress(address: String) {
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(address)
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performImeAction()
    }

    private fun capture(name: String) {
        val context = instrumentation.targetContext
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "scenario-$name")
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("BACKTEST-SCREENSHOT: scenario-$name")
    }

    @Test(timeout = 300_000)
    fun backTabsSectionsAndExit() {
        val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse()
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody(
                                    "<html><head><title>page${request.path}</title></head>" +
                                        "<body><h1>page${request.path}</h1></body></html>",
                                )
                    }
                start()
            }
        try {
            val a = server.url("/a").toString()
            val b = server.url("/b").toString()
            val c = server.url("/c").toString()
            composeRule.waitForIdle()
            composeRule.waitUntil(
                20_000,
            ) { runCatching { composeRule.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed() }.isSuccess }

            // Page history: a -> b -> c, then Back twice returns to b and a.
            typeAddress(a)
            awaitUrl(a)
            val tab1 = tabManager.state.value.activeTabId!!
            val view1 = onMain { activeWebView()!! }
            onMain { view1.loadUrl(b) }
            awaitUrl(b)
            onMain { view1.loadUrl(c) }
            awaitUrl(c)
            capture("backtest-1-on-c.png")
            back()
            awaitUrl(b)
            back()
            awaitUrl(a)
            capture("backtest-2-back-on-a.png")
            assertFalse("Back must not leave the app while history exists", composeRule.activity.isFinishing)

            // History start: Back returns to the search screen (tab url cleared, blank page), app stays open.
            assertFalse(onMain { view1.canGoBack() })
            back()
            awaitUrl("about:blank")
            assertEquals("", tabManager.state.value.tabs.first { it.id == tab1 }.url)
            capture("backtest-3-search-return.png")
            back()
            assertFalse("Back on the search screen must not exit", composeRule.activity.isFinishing)

            // Tab switch keeps each tab's own WebView and history.
            typeAddress(b)
            awaitUrl(b)
            onMain { view1.loadUrl(c) }
            awaitUrl(c)
            val tab2 = tabManager.openTab(a).id
            awaitUrl(a)
            assertTrue("second tab has its own WebView", onMain { activeWebView() !== view1 })
            tabManager.switchTo(tab1)
            composeRule.waitForIdle()
            awaitUrl(c)
            assertSame("tab 1 keeps the same WebView", view1, onMain { activeWebView() })
            assertTrue("tab 1 keeps its history", onMain { view1.canGoBack() })
            back()
            awaitUrl(b)
            tabManager.switchTo(tab2)
            composeRule.waitForIdle()
            awaitUrl(a)

            // Section switch away and back keeps tab 1 and its WebView history.
            tabManager.switchTo(tab1)
            composeRule.waitForIdle()
            awaitUrl(b)
            composeRule.onNodeWithText("السجل").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("المتصفح").performClick()
            composeRule.waitForIdle()
            assertNotNull(tabManager.state.value.tabs.firstOrNull { it.id == tab1 })
            assertSame("WebView survives a section switch", view1, onMain { activeWebView() })
            awaitUrl(b)
            capture("backtest-4-after-section-switch.png")

            // Exit only through the explicit button.
            composeRule.onNodeWithTag("browser_exit").performClick()
            val end = System.currentTimeMillis() + 10_000
            while (!composeRule.activity.isFinishing && System.currentTimeMillis() < end) Thread.sleep(250)
            assertTrue("Exit button finishes the activity", composeRule.activity.isFinishing)
        } finally {
            server.shutdown()
        }
    }
}
