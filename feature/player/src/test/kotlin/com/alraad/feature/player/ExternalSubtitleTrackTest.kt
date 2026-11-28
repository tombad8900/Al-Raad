package com.alraad.feature.player

import com.alraad.feature.player.impl.externalSubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSubtitleTrackTest {
    @Test fun `vtt srt and ass files map to their media3 mime types`() {
        assertEquals("text/vtt", externalSubtitleTrack("content://docs/1", "movie.ar.vtt")!!.mimeType)
        assertEquals("application/x-subrip", externalSubtitleTrack("content://docs/2", "movie.en.srt")!!.mimeType)
        assertEquals("text/x-ssa", externalSubtitleTrack("content://docs/3", "movie.ass")!!.mimeType)
        assertEquals("text/x-ssa", externalSubtitleTrack("content://docs/4", "movie.ssa")!!.mimeType)
    }

    @Test fun `extension falls back to the uri when the display name has none`() {
        val track = externalSubtitleTrack("file:///sdcard/subs/film.srt", null)
        assertEquals("application/x-subrip", track!!.mimeType)
        assertEquals("film.srt", track.label)
    }

    @Test fun `unsupported extensions are rejected`() {
        assertNull(externalSubtitleTrack("content://docs/5", "notes.txt"))
        assertNull(externalSubtitleTrack("content://docs/6", null))
    }

    @Test fun `local files never carry request headers`() {
        val track = externalSubtitleTrack("content://docs/7", "a.vtt")!!
        assertTrue(track.requestHeaders.isEmpty())
        assertEquals("", track.sourcePageUrl)
        assertEquals("", track.sourceTabId)
        assertEquals("external:content://docs/7", track.id)
    }
}
