package com.alraad.feature.browser

import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamListener
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.sniffer.StreamSniffer
import com.alraad.feature.browser.ui.PageTabAssociator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Verifies WebView request delivery into the sniffer, including page correlation. */
@RunWith(RobolectricTestRunner::class)
class WebViewSnifferDeliveryTest {
    private val logger =
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
    private val allowAll =
        object : AdBlocker {
            override suspend fun shouldBlock(url: String) = false

            override suspend fun cosmeticCss(host: String) = ""
        }

    private fun request(
        url: String,
        mainFrame: Boolean = false,
        headers: Map<String, String> = mapOf("Referer" to "https://page.example"),
    ) = object : WebResourceRequest {
        override fun getUrl() = android.net.Uri.parse(url)

        override fun isForMainFrame() = mainFrame

        override fun isRedirect() = false

        override fun hasGesture() = false

        override fun getMethod() = "GET"

        override fun getRequestHeaders() = headers
    }

    @Test fun `webview requests reach the sniffer and license attaches to the page stream`() {
        val sniffer = StreamSniffer(logger)
        val updates = mutableListOf<StreamUrl>()
        sniffer.registerListener(
            object : StreamListener {
                override fun onStreamDetected(stream: StreamUrl) = Unit

                override fun onStreamUpdated(stream: StreamUrl) {
                    updates += stream
                }
            },
        )
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        val view = WebView(org.robolectric.RuntimeEnvironment.getApplication())
        val client =
            helper.webViewClient(
                onRequestObserved = { url, headers, pageUrl ->
                    sniffer.onRequestObserved(url, headers, null, StreamSource.NETWORK, pageUrl)
                },
            )
        // Drive the UI-thread navigation callback explicitly: correlation must use the
        // cached page URL, not view.url (unreadable off the WebView UI thread; it is
        // null here precisely so a view.url read would fail the correlation).
        client.onPageStarted(view, "https://page-a.example/watch", null)
        client.shouldInterceptRequest(view, request("https://cdn-a.example/movie.m3u8"))
        client.shouldInterceptRequest(view, request("https://lic.example/widevine"))
        assertEquals(1, updates.size)
        assertEquals("https://lic.example/widevine", updates[0].drmLicenseUrl)
    }

    @Test fun `main-frame interception updates the cached page URL without reading view state`() {
        val observed = mutableListOf<String?>()
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        val view = WebView(org.robolectric.RuntimeEnvironment.getApplication())
        val client = helper.webViewClient(onRequestObserved = { _, _, pageUrl -> observed += pageUrl })
        client.onPageStarted(view, "https://first.example/", null)
        client.shouldInterceptRequest(view, request("https://second.example/nav", mainFrame = true))
        client.shouldInterceptRequest(view, request("https://cdn-b.example/movie.m3u8"))
        assertEquals(listOf("https://second.example/nav", "https://second.example/nav"), observed)
    }

    @Test
    fun `pushState document context reaches iframe detection with captured headers`() {
        val initialPage = "https://top.example/watch"
        val pushedPage = "https://top.example/watch/after-push-state"
        val associator = PageTabAssociator()
        associator.recordRequest("tab-a", initialPage)
        val captured = mutableListOf<Triple<String?, String?, Map<String, String>>>()
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        val view = WebView(org.robolectric.RuntimeEnvironment.getApplication())
        val client =
            helper.webViewClient(
                onPageDocumentStarted = { _, url ->
                    associator.onPageStartedWithContext(url, "tab-a").documentId
                },
                onPageUrlChanged = { _, previousUrl, url, _ ->
                    associator.onPageUrlChanged(previousUrl, url)?.documentId
                },
                onRequestObservedWithContext = { url, headers, pageUrl, documentId ->
                    captured += Triple(pageUrl, documentId, headers)
                },
            )
        client.onPageStarted(view, initialPage, null)
        client.doUpdateVisitedHistory(view, pushedPage, false)
        val iframeHeaders =
            mapOf(
                "Referer" to "https://iframe.other-origin.example/frame",
                "Origin" to "https://iframe.other-origin.example",
            )

        client.shouldInterceptRequest(
            view,
            request(
                "https://media.cdn-other.example/movie/index.m3u8",
                headers = iframeHeaders,
            ),
        )

        assertEquals(pushedPage, captured.single().first)
        assertEquals(associator.associationForPage(pushedPage)?.documentId, captured.single().second)
        assertEquals(iframeHeaders, captured.single().third)
    }

}
