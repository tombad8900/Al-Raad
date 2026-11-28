package com.alraad.feature.browser

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.sniffer.FetchXhrCaptureScript
import com.alraad.feature.browser.sniffer.SnifferJsBridge
import com.alraad.feature.browser.sniffer.StreamObservationListener
import com.alraad.feature.browser.sniffer.StreamSniffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnifferJsBridgeTest {
    private val logger =
        object : DiagnosticsLogger {
            override fun log(category: DiagCategory, level: DiagLevel, message: String, details: Map<String, String>) = Unit
            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())
            override suspend fun exportAsText() = ""
            override suspend fun clear() = Unit
        }

    @Test
    fun `fetch and xhr metadata is tagged with native page and tab context`() {
        val sniffer = StreamSniffer(logger)
        val observed = mutableListOf<Triple<String?, String?, StreamUrl>>()
        sniffer.registerObservationListener(
            StreamObservationListener { stream, page, tab -> observed += Triple(page, tab, stream) },
        )
        val bridge = SnifferJsBridge(sniffer)
        val pageA = "https://page-a.example/watch"
        val tokenA = bridge.bindPage(pageA, "tab-a")

        bridge.onRequestObserved(payload(tokenA, pageA, "https://cdn.example/a.m3u8"))

        assertEquals(1, observed.size)
        assertEquals(pageA, observed.single().first)
        assertEquals("tab-a", observed.single().second)
        assertEquals(StreamSource.JS_BRIDGE, observed.single().third.source)
    }

    @Test
    fun `old document token cannot leak requests into a later tab`() {
        val sniffer = StreamSniffer(logger)
        val observed = mutableListOf<Pair<String?, String?>>()
        sniffer.registerObservationListener(StreamObservationListener { _, page, tab -> observed += page to tab })
        val bridge = SnifferJsBridge(sniffer)
        val oldPage = "https://old.example/watch"
        val oldToken = bridge.bindPage(oldPage, "tab-old")
        val newPage = "https://new.example/watch"
        val newToken = bridge.bindPage(newPage, "tab-new")

        bridge.onRequestObserved(payload(oldToken, oldPage, "https://cdn.example/stale.m3u8"))
        bridge.onRequestObserved(payload(newToken, newPage, "https://cdn.example/current.m3u8"))

        assertEquals(listOf(newPage to "tab-new"), observed)
    }

    @Test
    fun `malformed oversized non-http and mismatched page payloads are ignored`() {
        val sniffer = StreamSniffer(logger)
        var detections = 0
        sniffer.registerObservationListener(StreamObservationListener { _, _, _ -> detections++ })
        val bridge = SnifferJsBridge(sniffer)
        val page = "https://page.example/watch"
        val token = bridge.bindPage(page, "tab-1")

        bridge.onRequestObserved("{")
        bridge.onRequestObserved("x".repeat(16_385))
        bridge.onRequestObserved(payload(token, page, "file:///private/file.m3u8"))
        bridge.onRequestObserved(payload(token, "https://other.example/", "https://cdn.example/other.m3u8"))

        assertEquals(0, detections)
    }

    @Test
    fun `credential headers are excluded and safe headers remain bounded`() {
        val sniffer = StreamSniffer(logger)
        var captured: StreamUrl? = null
        sniffer.registerObservationListener(StreamObservationListener { stream, _, _ -> captured = stream })
        val bridge = SnifferJsBridge(sniffer)
        val page = "https://page.example/"
        val token = bridge.bindPage(page, "tab-1")
        val headers =
            JSONObject()
                .put("accept", "application/vnd.apple.mpegurl")
                .put("range", "bytes=0-")
                .put("cookie", "session=secret")
                .put("authorization", "Bearer secret")
                .put("x-api-key", "secret")
                .put("content-type", "application/json")
                .put("accept-language", "ar")
                .put("range-extra", "ignored")

        bridge.onRequestObserved(payload(token, page, "https://cdn.example/video.m3u8", headers))

        assertEquals(mapOf("Accept" to "application/vnd.apple.mpegurl", "Range" to "bytes=0-", "Content-Type" to "application/json"), captured?.headers)
        assertFalse(captured?.headers?.keys?.any { it.equals("Cookie", true) || it.equals("Authorization", true) } == true)
    }

    @Test
    fun `observer script wraps fetch and xhr without reading response bodies`() {
        val script = FetchXhrCaptureScript.build("12345678-1234-1234-1234-123456789abc")

        assertTrue(script.contains("window.fetch = function"))
        assertTrue(script.contains("XMLHttpRequest.prototype.send"))
        assertTrue(script.contains("window.AlRaadSniffer.onRequestObserved"))
        assertFalse(script.contains("responseText"))
        assertFalse(script.contains("response.body"))
        assertFalse(script.contains("authorization"))
        assertFalse(script.contains("cookie"))
    }

    private fun payload(
        token: String,
        page: String,
        url: String,
        headers: JSONObject = JSONObject().put("accept", "application/vnd.apple.mpegurl"),
    ): String =
        JSONObject()
            .put("token", token)
            .put("pageUrl", page)
            .put("url", url)
            .put("method", "GET")
            .put("headers", headers)
            .toString()
}
