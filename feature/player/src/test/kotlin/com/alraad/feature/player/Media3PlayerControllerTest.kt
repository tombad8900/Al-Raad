package com.alraad.feature.player

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.PlaybackRequest
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.contracts.SubtitleTrack
import com.alraad.feature.player.impl.Media3PlayerController
import com.alraad.feature.player.impl.PlaybackRequestHeaderRouter
import com.alraad.feature.player.impl.addExternalSubtitle
import com.alraad.feature.player.impl.externalSubtitleTrack
import com.alraad.feature.player.impl.switchSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Media3PlayerControllerTest {
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

    private val controller = Media3PlayerController(ApplicationProvider.getApplicationContext(), noopLogger)

    @After fun tearDown() = controller.release()

    @Test fun `addExternalSubtitle attaches the track to the active request without leaking headers`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example.com/v.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
                headers = mapOf("Cookie" to "video-session-secret"),
            )
        controller.play(stream)
        val track = externalSubtitleTrack("content://docs/9", "movie.ar.vtt")!!
        controller.addExternalSubtitle(track)

        val request = requireNotNull(controller.currentPlaybackRequest.value)
        assertEquals(listOf(track), request.subtitles)
        val routed =
            PlaybackRequestHeaderRouter.headersForRequest(
                Uri.parse(track.url),
                stream.headers,
                request.subtitles,
            )
        assertFalse(routed.containsKey("Cookie"))
        assertTrue(routed.isEmpty())
    }

    @Test fun `switchSource rebuilds on the selected source and keeps subtitles`() {
        val dash =
            StreamUrl(
                url = "https://cdn.example.com/a.mpd",
                format = StreamFormat.DASH,
                source = StreamSource.NETWORK,
                headers = mapOf("Cookie" to "dash-session"),
            )
        val hls =
            StreamUrl(
                url = "https://cdn.example.com/a.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
            )
        val track = externalSubtitleTrack("content://docs/11", "movie.ar.vtt")!!
        controller.play(PlaybackRequest(sources = listOf(dash, hls), subtitles = listOf(track)))
        controller.switchSource(1)

        val request = requireNotNull(controller.currentPlaybackRequest.value)
        assertEquals(1, request.selectedSourceIndex)
        assertEquals(hls, request.selectedStream)
        assertEquals(hls, controller.currentStream.value)
        assertEquals(listOf(track), request.subtitles)
        assertNotNull(controller.currentPlayer())
    }

    @Test fun `switchSource ignores out-of-range and already-selected indexes`() {
        val hls =
            StreamUrl(
                url = "https://cdn.example.com/a.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
            )
        val mp4 =
            StreamUrl(
                url = "https://cdn.example.com/v.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
            )
        controller.play(PlaybackRequest(sources = listOf(hls, mp4)))
        val before = controller.currentPlaybackRequest.value
        controller.switchSource(5)
        controller.switchSource(0)
        assertTrue(controller.currentPlaybackRequest.value === before)
    }

    @Test fun `addExternalSubtitle is a no-op without playback and ignores duplicate urls`() {
        val track = externalSubtitleTrack("content://docs/10", "a.srt")!!
        controller.addExternalSubtitle(track)
        assertNull(controller.currentPlaybackRequest.value)

        val stream =
            StreamUrl(
                url = "https://cdn.example.com/v.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
                headers = emptyMap(),
            )
        controller.play(PlaybackRequest(sources = listOf(stream), subtitles = listOf(track)))
        controller.addExternalSubtitle(track)
        assertEquals(1, controller.currentPlaybackRequest.value!!.subtitles.size)
    }

    @Test fun `play creates a player with headers, pause seek release work`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example.com/v.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
                headers = mapOf("Referer" to "https://page.example.com", "Cookie" to "session=abc"),
            )
        controller.play(stream)
        assertNotNull(controller.currentPlayer())
        controller.pause()
        controller.seekTo(1000)
        controller.release()
        assertNull(controller.currentPlayer())
    }

    @Test fun `release frees the player but preserves the stream selection`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example.com/v.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
                headers = emptyMap(),
            )
        controller.play(stream)
        controller.release()
        assertNull(controller.currentPlayer())
        assertEquals(stream, controller.currentStream.value)
    }

    @Test fun `stop frees the player and clears the stream selection`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example.com/v.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
                headers = emptyMap(),
            )
        controller.play(stream)
        controller.stop()
        assertNull(controller.currentPlayer())
        assertNull(controller.currentStream.value)
    }

    @Test fun `play rebuilds from a preserved selection after release`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example.com/v.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
                headers = emptyMap(),
            )
        controller.play(stream)
        controller.release()
        controller.play(stream)
        assertNotNull(controller.currentPlayer())
        assertEquals(stream, controller.currentStream.value)
    }

    @Test fun `play propagates stream drm license url and license request headers to the media item`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example.com/v.mpd",
                format = StreamFormat.DASH,
                source = StreamSource.NETWORK,
                headers = mapOf("Referer" to "https://page.example.com"),
                drmLicenseUrl = "https://lic.example/widevine",
                drmLicenseRequestHeaders = mapOf("Authorization" to "Bearer t"),
            )
        controller.play(stream)
        val drm = controller.currentPlayer()?.currentMediaItem?.localConfiguration?.drmConfiguration
        assertNotNull(drm)
        assertEquals("https://lic.example/widevine", drm?.licenseUri?.toString())
        assertEquals(mapOf("Authorization" to "Bearer t"), drm?.licenseRequestHeaders)
    }

    @Test
    fun `subtitle request headers never inherit video credentials`() {
        val streamHeaders =
            mapOf(
                "Cookie" to "video-session=secret",
                "Authorization" to "Bearer video-secret",
                "Referer" to "https://video.example/watch",
                "User-Agent" to "VideoBrowser/1",
            )
        val subtitle =
            SubtitleTrack(
                id = "tab:page:subtitle",
                url = "https://subtitle.example/ar.vtt",
                mimeType = "text/vtt",
                language = "ar",
                label = "Arabic",
                sourcePageUrl = "https://page.example/watch",
                sourceTabId = "tab-1",
                requestHeaders =
                    mapOf(
                        "Accept" to "text/vtt",
                        "User-Agent" to "SubtitleBrowser/2",
                        "Cookie" to "must-not-be-sent",
                        "Authorization" to "must-not-be-sent",
                    ),
            )

        val subtitleHeaders =
            PlaybackRequestHeaderRouter.headersForRequest(Uri.parse(subtitle.url), streamHeaders, listOf(subtitle))
        val videoHeaders =
            PlaybackRequestHeaderRouter.headersForRequest(Uri.parse("https://video.example/manifest.m3u8"), streamHeaders, listOf(subtitle))

        assertEquals("text/vtt", subtitleHeaders["Accept"])
        assertEquals("SubtitleBrowser/2", subtitleHeaders["User-Agent"])
        assertFalse(subtitleHeaders.containsKey("Cookie"))
        assertFalse(subtitleHeaders.containsKey("Authorization"))
        assertEquals("video-session=secret", videoHeaders["Cookie"])
        assertEquals("Bearer video-secret", videoHeaders["Authorization"])
    }

    @Test
    fun `playback request attaches external subtitle configurations to Media3 item`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example.com/movie.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
            )
        val subtitle =
            SubtitleTrack(
                id = "tab:page:ar",
                url = "https://cdn.example.com/movie.ar.vtt",
                mimeType = "text/vtt",
                language = "ar",
                label = "Arabic",
                sourcePageUrl = "https://page.example/watch",
                sourceTabId = "tab-1",
            )
        val request =
            PlaybackRequest(
                sources = listOf(stream),
                subtitles = listOf(subtitle),
                sourcePageUrl = subtitle.sourcePageUrl,
                sourceTabId = subtitle.sourceTabId,
            )

        controller.play(request)

        assertEquals(request, controller.currentPlaybackRequest.value)
        val configurations =
            controller.currentPlayer()?.currentMediaItem?.localConfiguration?.subtitleConfigurations
        assertEquals(1, configurations?.size)
        assertEquals("text/vtt", configurations?.single()?.mimeType)
        assertEquals("ar", configurations?.single()?.language)
    }

    @Test fun `switching streams replaces the player so UI state rebinding sees the new instance`() {
        val first =
            StreamUrl(url = "https://cdn.example.com/a.mp4", format = StreamFormat.MP4, source = StreamSource.NETWORK)
        val second =
            StreamUrl(url = "https://cdn.example.com/b.mp4", format = StreamFormat.MP4, source = StreamSource.NETWORK)
        controller.play(first)
        val firstPlayer = controller.currentPlayer()
        assertNotNull(firstPlayer)
        controller.play(second)
        val secondPlayer = controller.currentPlayer()
        assertNotNull(secondPlayer)
        assert(secondPlayer !== firstPlayer) { "play() must replace the player so recomposition rebinding observes a change" }
    }
}
