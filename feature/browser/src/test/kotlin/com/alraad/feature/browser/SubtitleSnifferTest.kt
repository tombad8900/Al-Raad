package com.alraad.feature.browser

import com.alraad.feature.browser.sniffer.SubtitleSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleSnifferTest {
    @Test
    fun `captures VTT SRT and ASS with language and page tab identity`() {
        val sniffer = SubtitleSniffer()
        val observed = mutableListOf<com.alraad.core.common.contracts.SubtitleTrack>()
        sniffer.registerListener { observed += it }

        val vtt =
            sniffer.onRequestObserved(
                "https://sub.example/show.ar.vtt",
                mapOf("Accept-Language" to "ar"),
                "https://site.example/watch",
                "tab-a",
            )
        val srt =
            sniffer.onRequestObserved(
                "https://sub.example/show.srt?lang=en",
                emptyMap(),
                "https://site.example/watch",
                "tab-a",
            )
        val ass =
            sniffer.onRequestObserved(
                "https://sub.example/show.ass",
                emptyMap(),
                "https://site.example/watch",
                "tab-b",
            )

        assertNotNull(vtt)
        assertNotNull(srt)
        assertNotNull(ass)
        assertEquals("text/vtt", vtt?.mimeType)
        assertEquals("application/x-subrip", srt?.mimeType)
        assertEquals("text/x-ssa", ass?.mimeType)
        assertEquals("ar", vtt?.language)
        assertEquals("en", srt?.language)
        assertFalse(vtt?.id == ass?.id)
        assertEquals(3, observed.size)
    }

    @Test
    fun `stores only safe request headers and deduplicates by tab and page`() {
        val sniffer = SubtitleSniffer()
        val observed = mutableListOf<com.alraad.core.common.contracts.SubtitleTrack>()
        sniffer.registerListener { observed += it }
        val headers =
            mapOf(
                "Cookie" to "session=secret",
                "Authorization" to "Bearer secret",
                "Referer" to "https://private.example/",
                "Origin" to "https://private.example",
                "Accept-Language" to "ar",
                "User-Agent" to "Mozilla/5.0",
            )

        val first =
            sniffer.onRequestObserved(
                "https://sub.example/captions.vtt",
                headers,
                "https://site.example/watch",
                "tab-a",
            )
        val duplicate =
            sniffer.onRequestObserved(
                "https://sub.example/captions.vtt",
                headers,
                "https://site.example/watch",
                "tab-a",
            )
        val otherTab =
            sniffer.onRequestObserved(
                "https://sub.example/captions.vtt",
                headers,
                "https://site.example/watch",
                "tab-b",
            )

        assertNotNull(first)
        assertNull(duplicate)
        assertNotNull(otherTab)
        assertEquals(mapOf("Accept-Language" to "ar", "User-Agent" to "Mozilla/5.0"), first?.requestHeaders)
        assertTrue(observed.all { "Cookie" !in it.requestHeaders && "Authorization" !in it.requestHeaders })
        assertTrue(observed.all { "Referer" !in it.requestHeaders && "Origin" !in it.requestHeaders })
    }
}
