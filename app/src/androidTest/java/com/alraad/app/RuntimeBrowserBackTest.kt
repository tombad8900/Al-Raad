package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Runtime acceptance for browser back, History-driven tab creation, and section retention.
 * There is currently no user-facing tab strip or create/switch-tab control in BrowserScreen;
 * this test deliberately does not call TabManager.openTab/switchTo as a substitute for UI.
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

    @Before
    fun resetTabs() {
        // reset() destroys pooled WebViews and must run on the UI thread.
        instrumentation.runOnMainSync { tabManager.reset() }
    }

    @After
    fun cleanTabs() {
        // Also clean up after assertion failures so pooled WebViews cannot leak into the next test.
        instrumentation.runOnMainSync { tabManager.reset() }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Must only be called from onMain: obtaining the pool and reading WebViews are UI-thread work. */
    private fun activeWebViewOnMain(): WebView? {
        val id = tabManager.state.value.activeTabId ?: return null
        return tabManager.webViewPool(instrumentation.targetContext.applicationContext).get(id)
    }

    private fun pooledWebViewOnMain(tabId: String): WebView? =
        tabManager.webViewPool(instrumentation.targetContext.applicationContext).get(tabId)

    private fun url(): String? = onMain { activeWebViewOnMain()?.url }

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
        val state = tabManager.state.value
        assertEquals(
            "WebView url (tabs=${state.tabs.map { it.id.take(4) to it.url }} active=${state.activeTabId?.take(4)} " +
                "viewExists=${onMain { activeWebViewOnMain() != null }} ${uiDiagnostics()})",
            expected,
            url(),
        )
    }

    private fun waitForBrowserAddressBar(timeoutMs: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            runCatching {
                composeRule.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForText(
        text: String,
        timeoutMs: Long = 20_000,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            runCatching { composeRule.onAllNodes(hasText(text)).onFirst().assertIsDisplayed() }.isSuccess
        }
    }

    /** Wait for both the loading indicator to appear and the WebView load to finish. */
    private fun awaitLoadFinished(timeoutMs: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            runCatching { composeRule.onNodeWithTag("page_loading").assertIsDisplayed() }.isSuccess
        }
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            runCatching { composeRule.onNodeWithTag("page_loading").assertIsDisplayed() }.isFailure
        }
    }

    private fun uiDiagnostics(): String =
        onMain {
            val activity = composeRule.activity

            fun count(view: android.view.View): Int {
                val ownView = if (view is WebView) 1 else 0
                val group = view as? android.view.ViewGroup ?: return ownView
                return ownView + (0 until group.childCount).sumOf { count(group.getChildAt(it)) }
            }
            "activity=${activity.lifecycle.currentState} finishing=${activity.isFinishing} " +
                "webViewsInWindow=${count(activity.window.decorView)} " +
                "poolHasActive=${activeWebViewOnMain() != null}"
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
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "scenario-$name")
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("BACKTEST-SCREENSHOT: scenario-$name")
    }

    @Test(timeout = 300_000)
    fun backHistoryCreatesTabAndSectionsRetainWebViews() {
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
            val uniquePath = "/a-${System.currentTimeMillis()}"
            val a = server.url(uniquePath).toString()
            val aTitle = "page$uniquePath"
            val b = server.url("/b").toString()
            val c = server.url("/c").toString()
            composeRule.waitForIdle()
            composeRule.waitUntil(20_000) {
                runCatching {
                    composeRule.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
                }.isSuccess
            }

            // Page history: a -> b -> c, then Back twice returns to b and a.
            typeAddress(a)
            awaitUrl(a)
            awaitLoadFinished()
            val tab1 = checkNotNull(tabManager.state.value.activeTabId)
            val view1 = checkNotNull(onMain { activeWebViewOnMain() })
            onMain { view1.loadUrl(b) }
            awaitUrl(b)
            awaitLoadFinished()
            onMain { view1.loadUrl(c) }
            awaitUrl(c)
            awaitLoadFinished()
            capture("backtest-1-on-c.png")
            back()
            awaitUrl(b)
            back()
            awaitUrl(a)
            capture("backtest-2-back-on-a.png")
            assertFalse("Back must not leave the app while history exists", composeRule.activity.isFinishing)

            // At the history start, Back returns to the search screen and does not exit.
            assertFalse(onMain { view1.canGoBack() })
            back()
            awaitUrl("about:blank")
            assertEquals("", tabManager.state.value.tabs.first { it.id == tab1 }.url)
            capture("backtest-3-search-return.png")
            back()
            assertFalse("Back on the search screen must not exit", composeRule.activity.isFinishing)

            // Rebuild a -> b -> c in tab 1 so its own history has a known previous entry.
            typeAddress(a)
            awaitUrl(a)
            awaitLoadFinished()
            onMain { view1.loadUrl(b) }
            awaitUrl(b)
            awaitLoadFinished()
            onMain { view1.loadUrl(c) }
            awaitUrl(c)
            awaitLoadFinished()
            back()
            awaitUrl(b)
            assertTrue(onMain { view1.canGoBack() })
            assertEquals(
                "tab 1 retains its own previous page",
                a,
                onMain {
                    val history = view1.copyBackForwardList()
                    history.getItemAtIndex(history.currentIndex - 1).url
                },
            )

            // Real user path: open History, tap the visited page row, and let the app
            // create/navigate the new tab through HistoryBookmarkNavigationViewModel.
            composeRule.onNodeWithText("السجل").performClick()
            waitForText(aTitle)
            composeRule.onAllNodes(hasText(aTitle)).onFirst().performClick()
            composeRule.waitForIdle()
            awaitUrl(a)

            val tab2 = checkNotNull(tabManager.state.value.activeTabId)
            assertNotEquals("History opens a distinct tab", tab1, tab2)
            val view2 = checkNotNull(onMain { activeWebViewOnMain() })
            assertNotSame("History tab owns a distinct WebView", view1, view2)
            assertEquals("new tab opens the selected History URL", a, onMain { view2.url })
            assertEquals("History navigation loads the fixture document", aTitle, onMain { view2.title })
            assertFalse("new tab starts with independent one-page history", onMain { view2.canGoBack() })

            // The original tab remains in TabManager with the exact same WebView and history.
            assertNotNull(tabManager.state.value.tabs.firstOrNull { it.id == tab1 })
            assertSame("original WebView remains pooled", view1, onMain { pooledWebViewOnMain(tab1) })
            assertEquals("original page remains at its prior URL", b, onMain { view1.url })
            assertTrue("original WebView history survives History tab creation", onMain { view1.canGoBack() })

            // Switching sections is a user-facing path; returning to Browser retains the active tab.
            composeRule.onNodeWithText("المكتبة").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("المتصفح").performClick()
            composeRule.waitForIdle()
            assertEquals(tab2, tabManager.state.value.activeTabId)
            assertSame(
                "active History-created WebView survives section navigation",
                view2,
                onMain { activeWebViewOnMain() },
            )
            assertSame("background original WebView is still retained", view1, onMain { pooledWebViewOnMain(tab1) })
            assertTrue("background original WebView history is still retained", onMain { view1.canGoBack() })
            awaitUrl(a)
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
