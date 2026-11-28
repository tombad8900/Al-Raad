package com.alraad.feature.browser

import com.alraad.core.common.contracts.*
import com.alraad.feature.browser.sniffer.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnifferJsBridgeTest {
    private val logs = mutableListOf<String>()
    private val logger = object : DiagnosticsLogger {
        override fun log(category: DiagCategory, level: DiagLevel, message: String, details: Map<String, String>) {
            logs += details.toString()
        }
        override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())
        override suspend fun exportAsText() = ""
        override suspend fun clear() = Unit
    }

    @Test fun `observations retain the native page tab and reject stale document tokens`() {
        val sniffer = StreamSniffer(logger)
        var seen: Triple<String?, String?, StreamUrl>? = null
        sniffer.registerObservationListener(StreamObservationListener { stream, page, tab -> seen = Triple(page, tab, stream) })
        val bridge = SnifferJsBridge(sniffer)
        val oldPage = "https://old.example/"
        val oldToken = bridge.bindPage(oldPage, "tab-old")
        val page = "https://new.example/"
        val token = bridge.bindPage(page, "tab-new")
        bridge.onRequestObserved(payload(oldToken, oldPage, "https://cdn.example/stale.m3u8"))
        bridge.onRequestObserved(payload(token, page, "https://cdn.example/live.m3u8"))
        assertEquals(page, seen?.first)
        assertEquals("tab-new", seen?.second)
        assertEquals(StreamSource.JS_BRIDGE, seen?.third?.source)
    }

    @Test fun `malformed oversized mismatched and non-http inputs are ignored`() {
        val sniffer = StreamSniffer(logger)
        var count = 0
        sniffer.registerObservationListener(StreamObservationListener { _, _, _ -> count++ })
        val bridge = SnifferJsBridge(sniffer)
        val page = "https://page.example/"
        val token = bridge.bindPage(page, "tab")
        listOf("{", "x".repeat(16_385), payload(token, page, "file:///private/a.m3u8"),
            payload(token, "https://other.example/", "https://cdn.example/a.m3u8"))
            .forEach(bridge::onRequestObserved)
        assertEquals(0, count)
    }

    @Test fun `credentials remain available to playback but never enter diagnostics`() {
        val sniffer = StreamSniffer(logger)
        var stream: StreamUrl? = null
        sniffer.registerObservationListener(StreamObservationListener { value, _, _ -> stream = value })
        val bridge = SnifferJsBridge(sniffer)
        val page = "https://page.example/"
        val token = bridge.bindPage(page, "tab")
        val headers = JSONObject().put("accept", "application/vnd.apple.mpegurl")
            .put("authorization", "Bearer auth-secret").put("cookie", "cookie-secret")
        bridge.onRequestObserved(payload(token, page, "https://cdn.example/a.m3u8", headers))
        bridge.onRequestObserved(payload(token, page, "https://license.example/widevine", headers))
        assertEquals("Bearer auth-secret", stream?.headers?.get("Authorization"))
        assertEquals("Bearer auth-secret", stream?.drmLicenseRequestHeaders?.get("Authorization"))
        assertFalse(stream?.headers?.containsKey("Cookie") == true)
        val diagnosticText = logs.toString()
        assertFalse(diagnosticText.contains("auth-secret"))
        assertFalse(diagnosticText.contains("cookie-secret"))
        assertFalse(diagnosticText.contains("Authorization"))
        assertFalse(diagnosticText.contains("Cookie"))
    }

    @Test fun `oversized header values are dropped and script observes fetch xhr only`() {
        val bridge = SnifferJsBridge(StreamSniffer(logger))
        val page = "https://page.example/"
        val token = bridge.bindPage(page, "tab")
        var stream: StreamUrl? = null
        val sniffer = StreamSniffer(logger)
        sniffer.registerObservationListener(StreamObservationListener { value, _, _ -> stream = value })
        val boundedBridge = SnifferJsBridge(sniffer)
        val boundedToken = boundedBridge.bindPage(page, "tab")
        boundedBridge.onRequestObserved(payload(boundedToken, page, "https://cdn.example/a.m3u8",
            JSONObject().put("accept", "text/plain").put("authorization", "x".repeat(1_025))))
        assertEquals(mapOf("Accept" to "text/plain"), stream?.headers)
        assertTrue(token.isNotEmpty())
        val script = FetchXhrCaptureScript.build("12345678-1234-1234-1234-123456789abc")
        assertTrue(script.contains("window.fetch = function") && script.contains("XMLHttpRequest.prototype.send"))
        assertFalse(script.contains("responseText") || script.contains("response.body") || script.contains("cookie"))
        assertTrue(bridge.isBoundTo(page, "tab"))
    }

    private fun payload(token: String, page: String, url: String, headers: JSONObject = JSONObject().put("accept", "application/vnd.apple.mpegurl")) =
        JSONObject().put("token", token).put("pageUrl", page).put("url", url).put("method", "GET").put("headers", headers).toString()
}
