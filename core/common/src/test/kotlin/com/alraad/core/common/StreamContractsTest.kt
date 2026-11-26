package com.alraad.core.common

import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamContractsTest {
    @Test
    fun `headers default to empty map and size defaults to null`() {
        val stream = StreamUrl(url = "https://x.test/a.m3u8", format = StreamFormat.HLS, source = StreamSource.NETWORK)
        assertTrue(stream.headers.isEmpty())
        assertNull(stream.size)
    }

    @Test
    fun `headers are preserved exactly as given`() {
        val headers =
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14)",
                "Referer" to "https://source.test/page",
                "Cookie" to "session=abc123; token=zzz",
            )
        val stream =
            StreamUrl(
                url = "https://cdn.test/live/master.m3u8?token=secret-token",
                format = StreamFormat.HLS,
                source = StreamSource.JS_BRIDGE,
                headers = headers,
                size = 1024L,
            )
        assertEquals(headers, stream.headers)
        assertEquals("session=abc123; token=zzz", stream.headers["Cookie"])
        assertEquals(1024L, stream.size)
    }
}
