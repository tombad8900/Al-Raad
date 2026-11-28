package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * Runtime acceptance for browser back, History-driven tab creation, and section retention.
 * There is currently no user-facing tab strip or create/switch-tab control in BrowserScreen;
 * this test deliberately does not call TabManager.openTab/switchTo as a substitute for UI.
 * Pages are served by a local MockWebServer, so no external network is involved.
 */
class RuntimeBrowserBackTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    // Reset before Activity composition so BrowserScreen's initial-tab effect runs normally.
    // History-driven creation below remains a separate real UI path and verifies its own WebView.
    private val resetTabsBeforeActivity =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val tabManager =
                        EntryPointAccessors.fromApplication(
                            instrumentation.targetContext.applicationContext,
                            TabManagerTestEntryPoint::class.java,
                        ).tabManager()
                    instrumentation.runOnMainSync { tabManager.reset() }
                    base.evaluate()
                }
            }
        }

    @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(resetTabsBeforeActivity).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activeServer: MockWebServer? = null
    private val diagnostics by lazy {
        EntryPointAccessors
            .fromApplication(instrumentation.targetContext.applicationContext, TabManagerTestEntryPoint::class.java)
            .diagnosticsLogger()
    }
    private val tabManager: TabManager by lazy {
        EntryPointAccessors
            .fromApplication(instrumentation.targetContext.applicationContext, TabManagerTestEntryPoint::class.java)
            .tabManager()
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
        // Capture the real failure state first; the controls below load other URLs and must not mask it.
        val actual = url()
        val state = tabManager.state.value
        val failure =
            "WebView url (tabs=${state.tabs.map { it.id.take(4) to it.url }} active=${state.activeTabId?.take(4)} " +
                "viewExists=${onMain { activeWebViewOnMain() != null }} ${uiDiagnostics()} " +
                "serverRequests=${activeServer?.requestCount} ${serverProbe(expected)} "
        val port = activeServer?.port
        val controls =
            "directLocalhost=${webViewControl(expected)} " +
                "ipv4=${webViewControl("http://127.0.0.1:$port/ctl-v4")} " +
                "webviewLogs=${webViewLogs()})"
        assertEquals(failure + controls, expected, actual)
    }

    /** Control: load [target] straight into the active WebView (bypassing the app's tab sync) and report what happened. */
    private fun webViewControl(target: String): String {
        val before = activeServer?.requestCount ?: 0
        onMain { activeWebViewOnMain()?.loadUrl(target) }
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end && url() != target) Thread.sleep(250)
        val reached = url()
        return "[url=${reached == target} now=$reached requestsDelta=${(activeServer?.requestCount ?: 0) - before}]"
    }

    /** Wait for the initial tab and its actual attached WebView, not merely the address field. */
    private fun awaitBrowserReady(timeoutMs: Long = 20_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            onMain {
                val tabId = tabManager.state.value.activeTabId
                val webView = tabId?.let(::pooledWebViewOnMain)
                webView != null && webView.parent != null
            }
        }
        composeRule.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
    }

    private fun waitForText(
        text: String,
        timeoutMs: Long = 20_000,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            runCatching { composeRule.onAllNodes(hasText(text)).onFirst().assertIsDisplayed() }.isSuccess
        }
    }

    /** Wait until the expected URL has a completed document in the actual pooled WebView. */
    private fun awaitLoadFinished(
        expectedUrl: String,
        expectedTitle: String,
        timeoutMs: Long = 30_000,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            onMain {
                activeWebViewOnMain()?.let { webView ->
                    webView.url == expectedUrl && webView.progress == 100 && webView.title == expectedTitle
                } == true
            }
        }
    }

    private fun webViewBodyText(webView: WebView): String {
        val completed = CountDownLatch(1)
        var result: String? = null
        onMain {
            webView.evaluateJavascript("document.body.innerText") {
                result = it
                completed.countDown()
            }
        }
        check(completed.await(5, TimeUnit.SECONDS)) { "WebView body text evaluation timed out" }
        return checkNotNull(result)
    }

    /** Control read from the test process itself: shows whether the fixture server answers at all and where it is bound. */
    private fun serverProbe(url: String): String {
        val server = activeServer ?: return "probe=noServer"
        val before = server.requestCount
        val outcome =
            runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                "http=${conn.responseCode}"
            }.getOrElse { "err=${it.javaClass.simpleName}" }
        val localhostAddrs = runCatching { java.net.InetAddress.getAllByName("localhost").joinToString { it.hostAddress.orEmpty() } }
        return "probe[host=${server.hostName} port=${server.port} localhost=${localhostAddrs.getOrNull()} " +
            "$outcome requestsDelta=${server.requestCount - before}]"
    }

    private fun webViewLogs(): String =
        kotlinx.coroutines.runBlocking { diagnostics.exportAsText() }
            .lines()
            .filter { it.contains("Tab load") || it.contains("Load failed") || it.contains("HTTP") }
            .takeLast(10)
            .joinToString(" | ")

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

    @Test(timeout = 120_000)
    fun historyTrashDeletesEntryWithoutOpeningIt() {
        val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse()
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody(
                                    "<html><head><title>trash-fixture</title></head>" +
                                        "<body>trash fixture</body></html>",
                                )
                    }
                start()
            }
        activeServer = server
        try {
            val url = server.url("/trash-${System.currentTimeMillis()}").toString()
            composeRule.waitForIdle()
            awaitBrowserReady()
            typeAddress(url)
            awaitUrl(url)
            awaitLoadFinished(url, "trash-fixture")
            val activeTabId = checkNotNull(tabManager.state.value.activeTabId)
            val webView = checkNotNull(onMain { activeWebViewOnMain() })
            assertEquals(url, onMain { webView.url })
            assertTrue(webViewBodyText(webView).contains("trash fixture"))

            composeRule.onNodeWithText("السجل").performClick()
            val entryUrlTag = "history_list_entry_url_${url.hashCode()}"
            composeRule.waitUntil(20_000) {
                runCatching {
                    composeRule.onNodeWithTag(entryUrlTag, useUnmergedTree = true).assertIsDisplayed()
                }.isSuccess
            }
            composeRule.onNodeWithTag("history_list_delete_${url.hashCode()}").performClick()
            composeRule.waitUntil(15_000) {
                runCatching {
                    composeRule.onNodeWithTag(entryUrlTag, useUnmergedTree = true).assertIsDisplayed()
                }.isFailure
            }

            assertEquals(
                "trash must not create or switch tabs",
                activeTabId,
                tabManager.state.value.activeTabId,
            )
            assertSame(
                "trash must not replace the current WebView",
                webView,
                onMain { pooledWebViewOnMain(activeTabId) },
            )
            assertEquals("trash must not navigate the current WebView", url, onMain { webView.url })
            composeRule.onNodeWithText("مسح السجل / Clear all").assertIsDisplayed()

            composeRule.onNodeWithText("المتصفح").performClick()
            awaitBrowserReady()
            assertEquals(url, onMain { webView.url })
        } finally {
            server.shutdown()
        }
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
        activeServer = server
        try {
            val uniquePath = "/a-${System.currentTimeMillis()}"
            val a = server.url(uniquePath).toString()
            val aTitle = "page$uniquePath"
            val b = server.url("/b").toString()
            val c = server.url("/c").toString()
            composeRule.waitForIdle()
            awaitBrowserReady()

            // Page history: a -> b -> c, then Back twice returns to b and a.
            typeAddress(a)
            awaitUrl(a)
            awaitLoadFinished(a, aTitle)
            val tab1 = checkNotNull(tabManager.state.value.activeTabId)
            val view1 = checkNotNull(onMain { activeWebViewOnMain() })
            onMain { view1.loadUrl(b) }
            awaitUrl(b)
            awaitLoadFinished(b, "page/b")
            onMain { view1.loadUrl(c) }
            awaitUrl(c)
            awaitLoadFinished(c, "page/c")
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
            awaitLoadFinished(a, aTitle)
            onMain { view1.loadUrl(b) }
            awaitUrl(b)
            awaitLoadFinished(b, "page/b")
            onMain { view1.loadUrl(c) }
            awaitUrl(c)
            awaitLoadFinished(c, "page/c")
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
            composeRule
                .onAllNodes(hasTestTag("history_list_entry_url_${a.hashCode()}"), useUnmergedTree = true)
                .onFirst()
                .performClick()
            composeRule.waitForIdle()
            awaitBrowserReady()
            awaitUrl(a)
            awaitLoadFinished(a, aTitle)

            val tab2 = checkNotNull(tabManager.state.value.activeTabId)
            assertNotEquals("History opens a distinct tab", tab1, tab2)
            val view2 = checkNotNull(onMain { activeWebViewOnMain() })
            assertNotSame("History tab owns a distinct WebView", view1, view2)
            assertEquals("new tab opens the selected History URL", a, onMain { view2.url })
            assertEquals("History navigation loads the fixture document", aTitle, onMain { view2.title })
            assertTrue(webViewBodyText(view2).contains("page$uniquePath"))
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
