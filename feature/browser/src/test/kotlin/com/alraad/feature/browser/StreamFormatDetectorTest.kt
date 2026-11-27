package com.alraad.feature.browser

import com.alraad.core.common.contracts.StreamFormat
import com.alraad.feature.browser.sniffer.StreamFormatDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFormatDetectorTest {
    @Test fun `detects formats from extensions`() {
        assertEquals(StreamFormat.HLS, StreamFormatDetector.detect("https://cdn.example.com/master.m3u8"))
        assertEquals(StreamFormat.DASH, StreamFormatDetector.detect("https://cdn.example.com/manifest.mpd?tok=abc"))
        assertEquals(StreamFormat.MP4, StreamFormatDetector.detect("https://cdn.example.com/v.mp4#frag"))
        assertEquals(StreamFormat.WEBM, StreamFormatDetector.detect("https://cdn.example.com/v.webm"))
        assertEquals(StreamFormat.MKV, StreamFormatDetector.detect("https://cdn.example.com/v.mkv"))
    }

    @Test fun `content-type wins over extension and covers extensionless urls`() {
        assertEquals(StreamFormat.HLS, StreamFormatDetector.detect("https://x.example.com/stream", "application/vnd.apple.mpegurl"))
        assertEquals(StreamFormat.DASH, StreamFormatDetector.detect("https://x.example.com/a.mp4", "application/dash+xml; charset=utf-8"))
    }

    @Test fun `non-stream urls are unknown`() {
        assertEquals(StreamFormat.UNKNOWN, StreamFormatDetector.detect("https://example.com/page.html"))
        assertFalse(StreamFormatDetector.isStream("https://example.com/app.js"))
        assertTrue(StreamFormatDetector.isStream("https://example.com/live.m3u8"))
    }
}
