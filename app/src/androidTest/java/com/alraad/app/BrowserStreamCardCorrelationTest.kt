package com.alraad.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.feature.browser.tabs.TabManagerTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Exercises the real WebView -> request observer -> BrowserViewModel -> Compose card path.
 * The iframe has a different origin (same loopback host, distinct port) and is created only
 * after pushState changes the top-level document URL.
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
        val topLevel = MockWebServer()
        val iframeOrigin = MockWebServer()
        topLevel.start()
        iframeOrigin.start()
        try {
            val mediaUrl = iframeOrigin.url("/media/synthetic.m3u8").toString()
            iframeOrigin.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.path) {
                            "/frame" ->
                                MockResponse()
                                    .setHeader("Content-Type", "text/html; charset=utf-8")
                                    .setBody(
                                        "<!doctype html><html><body>" +
                                            "<img id='stream' src='$mediaUrl'>" +
                                            "</body></html>",
                                    )
                            "/media/synthetic.m3u8" ->
                                MockResponse()
                                    .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                                    .setBody("#EXTM3U\\n#EXT-X-VERSION:3\\n#EXT-X-ENDLIST")
                            else -> MockResponse().setResponseCode(404)
                        }

            val iframeUrl = iframeOrigin.url("/frame").toString()
            topLevel.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        "<!doctype html><html><body><script>" +
                            "history.pushState({fixture:true}, '', '/watch/after-push-state');" +
                            "const frame=document.createElement('iframe');" +
                            "frame.src='$iframeUrl';document.body.appendChild(frame);" +
                            "</script></body></html>",
                    ),
            )

            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).performTextInput(topLevel.url("/watch").toString())
            composeRule.onNode(hasSetTextAction()).performImeAction()

            composeRule.waitUntil(timeoutMillis = 30_000) {
                runCatching {
                    composeRule.onNodeWithText("روابط البث المكتشفة:", substring = true).assertIsDisplayed()
                    composeRule.onNodeWithText(mediaUrl, substring = true).assertIsDisplayed()
                }.isSuccess
            }
            composeRule.onNodeWithText("روابط البث المكتشفة:", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText(mediaUrl, substring = true).assertIsDisplayed()
            assertTrue("The cross-origin fixture stream must be served", iframeOrigin.requestCount >= 2)
        } finally {
            topLevel.shutdown()
            iframeOrigin.shutdown()
        }
    }
}
