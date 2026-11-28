package com.alraad.feature.browser

import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.fakes.FakeDiagnosticsLogger
import com.alraad.feature.browser.sniffer.FetchXhrCaptureScript
import com.alraad.feature.browser.sniffer.SnifferJsBridge
import com.alraad.feature.browser.sniffer.StreamObservationListener
import com.alraad.feature.browser.sniffer.StreamSniffer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnifferJsBridgeTest {
    private val logger = FakeDiagnosticsLogger()

    @Test fun `observations retain native page tab and reject stale document tokens`() {
        val sniffer = StreamSniffer(logger)
        var seen: Triple<String?, String?, StreamUrl>? = null
        sniffer.registerObservationListener(
            StreamObservationListener { stream, page, tab -> seen = Triple(page, tab, stream) },
        )
        val bridge = SnifferJsBridge(sniffer)
        val oldPage = "https://old.example/"
        val oldToken = bridge.bindPage(oldPage, "tab-old")
        val page = "https://new.example/"
        val token = bridge.bindPage(page, "tab-new")
        bridge.onRequestObserved(payload(oldToken, oldPage, "https://cdn.example/stale.m3u8"))
        bridge.onRequestObserved(payload(token, page, "https://cdn.example/live.m3u8"))
        assertEquals(
            Triple(page, "tab-new", StreamSource.JS_BRIDGE),
            Triple(seen?.first, seen?.second, seen?.third?.source),
        )
        val script = FetchXhrCaptureScript.build("12345678-1234-1234-1234-123456789abc")
        assertTrue(script.contains("window.fetch = function") && script.contains("XMLHttpRequest.prototype.send"))
    }

    @Test fun `malformed oversized mismatched and non-http inputs are ignored`() {
        val sniffer = StreamSniffer(logger)
        var count = 0
        sniffer.registerObservationListener(StreamObservationListener { _, _, _ -> count++ })
        val bridge = SnifferJsBridge(sniffer)
        val page = "https://page.example/"
        val token = bridge.bindPage(page, "tab")
        listOf(
            "{",
            "x".repeat(16_385),
            payload(token, page, "file:///private/a.m3u8"),
            payload(token, "https://other.example/", "https://cdn.example/a.m3u8"),
        ).forEach(bridge::onRequestObserved)
        assertEquals(0, count)
    }

    @Test fun `credentials remain available to playback but never enter diagnostics`() {
        val sniffer = StreamSniffer(logger)
        var stream: StreamUrl? = null
        sniffer.registerObservationListener(StreamObservationListener { value, _, _ -> stream = value })
        val bridge = SnifferJsBridge(sniffer)
        val page = "https://page.example/"
        val token = bridge.bindPage(page, "tab")
        val headers =
            JSONObject().put("accept", "application/vnd.apple.mpegurl")
                .put("authorization", "Bearer auth-secret").put("cookie", "cookie-secret")
        bridge.onRequestObserved(payload(token, page, "https://cdn.example/a.m3u8", headers))
        bridge.onRequestObserved(payload(token, page, "https://license.example/widevine", headers))
        assertEquals("Bearer auth-secret", stream?.headers?.get("Authorization"))
        assertEquals("Bearer auth-secret", stream?.drmLicenseRequestHeaders?.get("Authorization"))
        val diagnosticText = runBlocking { logger.exportAsText() }
        assertFalse(
            listOf("auth-secret", "cookie-secret", "Authorization", "Cookie").any(diagnosticText::contains),
        )
    }

    @Test fun `early observations flush only to the page that is bound and the buffer is capped`() {
        val sniffer = StreamSniffer(logger)
        val seen = mutableListOf<Pair<String?, String>>()
        sniffer.registerObservationListener(StreamObservationListener { stream, page, _ -> seen += page to stream.url })
        val bridge = SnifferJsBridge(sniffer)
        val page = "https://new.example/"
        val previous = "https://old.example/"
        bridge.onRequestObserved(payload("", previous, "https://cdn.example/old.m3u8"))
        repeat(40) { bridge.onRequestObserved(payload("", page, "https://cdn.example/early$it.m3u8")) }
        assertTrue(seen.isEmpty())
        bridge.bindPage(page, "tab")
        assertEquals(32, seen.size)
        assertTrue(seen.all { it.first == page && it.second.contains("early") })
        assertFalse(seen.any { it.second.endsWith("early0.m3u8") })
        bridge.onRequestObserved(payload("", previous, "https://cdn.example/late-old.m3u8"))
        assertEquals(32, seen.size)
    }

    @Test fun `static document-start script carries no token`() {
        val script = FetchXhrCaptureScript.buildStatic()
        assertTrue(script.contains("})(\"\");"))
        assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-").containsMatchIn(script))
    }

    private fun payload(
        token: String,
        page: String,
        url: String,
        headers: JSONObject? = null,
    ): String =
        JSONObject().put("token", token).put("pageUrl", page).put("url", url)
            .put("headers", headers ?: JSONObject().put("accept", "application/vnd.apple.mpegurl")).toString()
}
