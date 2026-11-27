package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewIntegrationHelperErrorLoggingTest {
    private val recorded = mutableListOf<DiagEntry>()
    private val logger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) {
                recorded += DiagEntry(0, 0L, category, level, message, details)
            }

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }
    private val allowAll =
        object : AdBlocker {
            override suspend fun shouldBlock(url: String): Boolean = false

            override fun cosmeticCss(host: String): String = ""
        }

    @Test fun `subresource load failures are logged, not dropped`() {
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        helper.logLoadError("https://cdn.example/ad.js", -2, "net::ERR_FAILED", isMainFrame = false)
        val entry = recorded.single { it.details["url"] == "https://cdn.example/ad.js" }
        assertEquals(DiagCategory.WEBVIEW, entry.category)
        assertEquals(DiagLevel.WARN, entry.level)
        assertEquals("false", entry.details["mainFrame"])
    }

    @Test fun `webViewClient dispatches subresource onReceivedError to the logger`() {
        // Regression: must exercise the actual WebViewClient callback so a dropped
        // isForMainFrame == false forward cannot pass unnoticed.
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        val client = helper.webViewClient()
        val view = android.webkit.WebView(org.robolectric.RuntimeEnvironment.getApplication())
        val request =
            object : android.webkit.WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse("https://cdn.example/ad.js")

                override fun isForMainFrame() = false

                override fun isRedirect() = false

                override fun hasGesture() = false

                override fun getMethod() = "GET"

                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
        val error = org.mockito.Mockito.mock(android.webkit.WebResourceError::class.java)
        org.mockito.Mockito.`when`(error.errorCode).thenReturn(-2)
        org.mockito.Mockito.`when`(error.description).thenReturn("net::ERR_FAILED")
        client.onReceivedError(view, request, error)
        val entry = recorded.single { it.details["url"] == "https://cdn.example/ad.js" }
        assertEquals(DiagCategory.WEBVIEW, entry.category)
        assertEquals(DiagLevel.WARN, entry.level)
        assertEquals("-2", entry.details["errorCode"])
        assertEquals("net::ERR_FAILED", entry.details["description"])
        assertEquals("false", entry.details["mainFrame"])
    }

    @Test fun `main-frame failures log at ERROR`() {
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        helper.logLoadError("https://site.example/", -6, "net::ERR_FILE_NOT_FOUND", isMainFrame = true)
        val entry = recorded.single { it.details["url"] == "https://site.example/" }
        assertEquals(DiagLevel.ERROR, entry.level)
        assertEquals("true", entry.details["mainFrame"])
    }

    @Test
    fun `webViewClient reports main frame start for tab correlation`() {
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        var startedUrl: String? = null
        val client = helper.webViewClient(onPageStart = { _, url -> startedUrl = url })
        val view = android.webkit.WebView(org.robolectric.RuntimeEnvironment.getApplication())

        client.onPageStarted(view, "https://page.example/watch", null)

        assertEquals("https://page.example/watch", startedUrl)
    }
