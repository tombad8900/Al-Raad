package com.alraad.feature.browser

import com.alraad.feature.browser.sniffer.SubtitleSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSnifferTest {
    private val sniffer = SubtitleSniffer()
    private val page = "https://site.example/watch"
    private val tab = "tab-1"

    @Test
    fun `captures VTT SRT and ASS with language metadata`() {
        val vtt =
            sniffer.onRequestObserved(
                "https://cdn.example/movie.ar.vtt",
                emptyMap(),
                page,
                tab,
            )
        val srt =
            sniffer.onRequestObserved(
                "https://cdn.example/movie.srt?lang=en",
                emptyMap(),
                page,
                tab,
            )
        val ass =
            sniffer.onRequestObserved(
                "https://cdn.example/movie.ass",
                emptyMap(),
                page,
                tab,
            )

        assertEquals("text/vtt", vtt?.mimeType)
        assertEquals("ar", vtt?.language)
        assertEquals("application/x-subrip", srt?.mimeType)
        assertEquals("en", srt?.language)
        assertEquals("text/x-ssa", ass?.mimeType)
    }

    @Test
    fun `keeps only safe per-subtitle headers and strips referer query`() {
        val track =
            sniffer.onRequestObserved(
                "https://cdn.example/captions.vtt",
                mapOf(
                    "Accept" to "text/vtt",
                    "User-Agent" to "Browser/1",
                    "Referer" to "https://user:password@site.example/watch?session=secret",
                    "Origin" to "https://site.example/path?token=secret",
                    "Cookie" to "session=secret",
                    "Authorization" to "Bearer secret",
                    "X-Api-Key" to "secret",
                ),
                page,
                tab,
            )

        assertEquals("text/vtt", track?.requestHeaders?.get("Accept"))
        assertEquals("Browser/1", track?.requestHeaders?.get("User-Agent"))
        assertEquals("https://site.example", track?.requestHeaders?.get("Referer"))
        assertEquals("https://site.example", track?.requestHeaders?.get("Origin"))
        assertFalse(track?.requestHeaders?.keys?.any { it.equals("Cookie", true) } ?: true)
        assertFalse(track?.requestHeaders?.keys?.any { it.equals("Authorization", true) } ?: true)
        assertFalse(track?.requestHeaders?.keys?.any { it.equals("X-Api-Key", true) } ?: true)
    }

    @Test
    fun `deduplicates track identity while allowing safe metadata refresh`() {
        val observations = mutableListOf<com.alraad.core.common.contracts.SubtitleTrack>()
        val listener = SubtitleSniffer.Listener(observations::add)
        sniffer.registerListener(listener)
        val url = "https://cdn.example/captions.vtt"

        val first = sniffer.onRequestObserved(url, mapOf("Accept" to "text/vtt"), page, tab)
        val duplicate = sniffer.onRequestObserved(url, mapOf("Accept-Language" to "ar"), page, tab)

        assertTrue(first != null)
        assertNull(duplicate)
        assertEquals(2, observations.size)
        assertEquals("ar", observations.last().requestHeaders["Accept-Language"])
        sniffer.unregisterListener(listener)
    }

    @Test
    fun `requires an HTTP page and owning tab`() {
        assertNull(sniffer.onRequestObserved("https://cdn.example/captions.vtt", emptyMap(), null, tab))
        assertNull(sniffer.onRequestObserved("https://cdn.example/captions.vtt", emptyMap(), page, null))
        assertNull(sniffer.onRequestObserved("file:///tmp/captions.vtt", emptyMap(), page, tab))
        assertNull(sniffer.onRequestObserved("https://cdn.example/video.mp4", emptyMap(), page, tab))
    }
}
