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

            override fun cosmeticCss(host: String) = ""
        }

    private fun request(url: String) =
        object : WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse(url)

            override fun isForMainFrame() = false

            override fun isRedirect() = false

            override fun hasGesture() = false

            override fun getMethod() = "GET"

            override fun getRequestHeaders() = mapOf("Referer" to "https://page.example")
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
        view.loadUrl("https://page-a.example/watch")
        val client =
            helper.webViewClient(
                onRequestObserved = { url, headers, pageUrl ->
                    sniffer.onRequestObserved(url, headers, null, StreamSource.NETWORK, pageUrl)
                },
            )
        client.shouldInterceptRequest(view, request("https://cdn-a.example/movie.m3u8"))
        client.shouldInterceptRequest(view, request("https://lic.example/widevine"))
        assertEquals(1, updates.size)
        assertEquals("https://lic.example/widevine", updates[0].drmLicenseUrl)
    }
}
