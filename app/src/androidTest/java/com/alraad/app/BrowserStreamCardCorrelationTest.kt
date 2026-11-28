package com.alraad.app

import android.net.Uri
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.feature.browser.tabs.TabManagerTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Exercises the real WebView -> request observer -> BrowserViewModel -> Compose card path.
 * The top-level fixture is loaded locally with an HTTPS origin; its data: iframe has a
 * distinct opaque origin and requests a synthetic HLS URL. No external stream is fetched.
 */
class BrowserStreamCardCorrelationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetBrowserTabs() {
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            TabManagerTestEntryPoint::class.java,
        ).tabManager().reset()
    }

    @Test
    fun crossOriginIframeHlsAppearsAfterTopLevelPushState() {
        composeRule.waitForIdle()
        val tabManager =
            EntryPointAccessors.fromApplication(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                TabManagerTestEntryPoint::class.java,
            ).tabManager()
        tabManager.openTab("about:blank")
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()

        var browserWebView: WebView? = null
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                onView(isAssignableFrom(WebView::class.java)).check { view, _ ->
                    browserWebView = view as WebView
                }
            }.isSuccess
        }
        val webView = requireNotNull(browserWebView)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ready = webView.progress == 100
            }
            ready
        }

        val baseUrl = "https://top.fixture.invalid/watch"
        val mediaUrl = "https://cdn.fixture.invalid/media/synthetic.m3u8"
        val iframeHtml =
            "<!doctype html><html><body><img src='$mediaUrl'></body></html>"
        val iframeDataUrl = "data:text/html," + Uri.encode(iframeHtml)
        val topLevelHtml =
            "<!doctype html><html><body><script>" +
                "history.pushState({fixture:true}, '', '/watch/after-push-state');" +
                "const frame=document.createElement('iframe');" +
                "frame.src='$iframeDataUrl';document.body.appendChild(frame);" +
                "</script></body></html>"

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.loadDataWithBaseURL(baseUrl, topLevelHtml, "text/html", "UTF-8", null)
        }

        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("روابط البث المكتشفة:", substring = true).assertIsDisplayed()
                composeRule.onNodeWithText(mediaUrl, substring = true).assertIsDisplayed()
            }.isSuccess
        }
    }
}
