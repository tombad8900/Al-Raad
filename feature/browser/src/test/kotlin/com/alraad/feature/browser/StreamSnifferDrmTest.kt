package com.alraad.feature.browser

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamListener
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.sniffer.StreamSniffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSnifferDrmTest {
    private val logs = mutableListOf<DiagEntry>()
    private val fakeLogger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) {
                logs += DiagEntry(timestamp = 0, category = category, level = level, message = message, details = details)
            }

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText() = ""

            override suspend fun clear() = Unit
        }

    @Test fun `license request attaches to the last detected stream and is not itself a stream`() {
        val sniffer = StreamSniffer(fakeLogger)
        val updates = mutableListOf<StreamUrl>()
        sniffer.registerListener(
            object : StreamListener {
                override fun onStreamDetected(stream: StreamUrl) = Unit

                override fun onStreamUpdated(stream: StreamUrl) {
                    updates += stream
                }
            },
        )
        sniffer.onRequestObserved("https://cdn.example/video.m3u8", emptyMap(), "application/vnd.apple.mpegurl")
        val result = sniffer.onRequestObserved("https://lic.example/widevine/license", mapOf("Authorization" to "Bearer t"))
        assertNull(result)
        assertEquals(1, updates.size)
        assertEquals("https://lic.example/widevine/license", updates[0].drmLicenseUrl)
        assertEquals(mapOf("Authorization" to "Bearer t"), updates[0].drmLicenseRequestHeaders)
        assertTrue(logs.any { it.message == "DRM license request detected" })
    }

    @Test fun `drm-prefixed media URL is still classified as a stream, not a license request`() {
        val sniffer = StreamSniffer(fakeLogger)
        val detected = sniffer.onRequestObserved("https://cdn.example/drm/manifest.mpd", emptyMap(), "application/dash+xml")
        assertEquals("https://cdn.example/drm/manifest.mpd", detected?.url)
        assertTrue(logs.none { it.message == "DRM license request detected" })
    }

    @Test fun `license request without a prior stream only logs diagnostics`() {
        val sniffer = StreamSniffer(fakeLogger)
        val updates = mutableListOf<StreamUrl>()
        sniffer.registerListener(
            object : StreamListener {
                override fun onStreamDetected(stream: StreamUrl) = Unit

                override fun onStreamUpdated(stream: StreamUrl) {
                    updates += stream
                }
            },
        )
        assertNull(sniffer.onRequestObserved("https://lic.example/drm/licensekey"))
        assertTrue(updates.isEmpty())
        assertTrue(logs.any { it.message == "DRM license request detected" })
    }
}
