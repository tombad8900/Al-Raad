package com.alraad.feature.browser

import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.ui.DetectedStreamItem
import com.alraad.feature.browser.ui.shouldShowStreamSelector
import com.alraad.feature.browser.ui.sortDetectedStreamItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedStreamPickerTest {
    @Test
    fun `sorts stream formats and removes duplicate URLs`() {
        val page = "https://page.example/watch"
        val tab = "tab-1"
        val dash =
            DetectedStreamItem(
                StreamUrl("https://cdn.example/z.mpd", StreamFormat.DASH, StreamSource.NETWORK),
                page,
                tab,
            )
        val hls =
            DetectedStreamItem(
                StreamUrl("https://cdn.example/a.m3u8", StreamFormat.HLS, StreamSource.NETWORK),
                page,
                tab,
            )
        val duplicate = hls.copy(stream = hls.stream.copy(headers = mapOf("Range" to "bytes=0-")))

        val sorted = sortDetectedStreamItems(listOf(hls, dash, duplicate))

        assertEquals(listOf(StreamFormat.DASH, StreamFormat.HLS), sorted.map { it.stream.format })
        assertEquals(2, sorted.size)
    }

    @Test
    fun `selector is hidden for one link and shown for multiple links`() {
        assertFalse(shouldShowStreamSelector(0))
        assertFalse(shouldShowStreamSelector(1))
        assertTrue(shouldShowStreamSelector(2))
    }
}
