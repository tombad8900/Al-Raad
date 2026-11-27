package com.alraad.feature.browser

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamListener
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.sniffer.StreamSniffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSnifferTest {
    private val noopLogger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) = Unit

            override fun observeLogs(): Flow<List<com.alraad.core.common.contracts.DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }

    @Test fun `detects stream with full headers and size, notifies listener`() {
        val sniffer = StreamSniffer(noopLogger)
        val captured = mutableListOf<StreamUrl>()
        sniffer.registerListener(
            object : StreamListener {
                override fun onStreamDetected(stream: StreamUrl) {
                    captured += stream
                }
            },
        )
        val headers =
            mapOf(
                "Referer" to "https://page.example.com/watch?v=1&token=secret",
                "Cookie" to "session=abc123",
                "Content-Length" to "1048576",
            )
        val stream = sniffer.onRequestObserved("https://cdn.example.com/live.m3u8", headers)
        assertEquals(StreamFormat.HLS, stream?.format)
        assertEquals(StreamSource.NETWORK, stream?.source)
        assertEquals(headers, stream?.headers)
        assertEquals(1048576L, stream?.size)
        assertEquals(1, captured.size)
    }

    @Test fun `dedups same url and headers, allows different headers`() {
        val sniffer = StreamSniffer(noopLogger)
        val headers = mapOf("Referer" to "https://a.example.com")
        assertTrue(sniffer.onRequestObserved("https://cdn.example.com/v.mp4", headers) != null)
        assertNull(sniffer.onRequestObserved("https://cdn.example.com/v.mp4", headers))
        assertTrue(sniffer.onRequestObserved("https://cdn.example.com/v.mp4", headers + ("Range" to "bytes=0-")) != null)
    }

    @Test fun `ignores non-stream requests`() {
        val sniffer = StreamSniffer(noopLogger)
        assertNull(sniffer.onRequestObserved("https://example.com/page.html"))
        assertNull(sniffer.onRequestObserved("https://example.com/api", contentType = "application/json"))
    }
}
