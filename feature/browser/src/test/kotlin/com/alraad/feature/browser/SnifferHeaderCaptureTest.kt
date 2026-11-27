package com.alraad.feature.browser

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.sniffer.StreamSniffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SnifferHeaderCaptureTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    @Test fun `headers recorded from a real m3u8 request are captured verbatim`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXTINF:1,\nseg.ts\n"),
        )
        server.start()
        val url = server.url("/live/master.m3u8").toString()
        val client = OkHttpClient()
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Referer", "https://page.example.com/watch?v=9&token=abc")
                .header("Cookie", "session=xyz789")
                .build(),
        ).execute().close()

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        val headers =
            (0 until recorded.headers.size).associate {
                recorded.headers.name(it) to recorded.headers.value(it)
            }
        val sniffer =
            StreamSniffer(
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
                },
            )
        val captured = mutableListOf<StreamUrl>()
        sniffer.registerListener(
            object : com.alraad.core.common.contracts.StreamListener {
                override fun onStreamDetected(stream: StreamUrl) {
                    captured += stream
                }
            },
        )
        val stream = sniffer.onRequestObserved(recorded.requestUrl.toString(), headers, recorded.getHeader("Content-Type"))

        assertNotNull(stream)
        assertEquals(StreamFormat.HLS, stream?.format)
        assertEquals("https://page.example.com/watch?v=9&token=abc", stream?.headers?.get("Referer"))
        assertEquals("session=xyz789", stream?.headers?.get("Cookie"))
    }
}
