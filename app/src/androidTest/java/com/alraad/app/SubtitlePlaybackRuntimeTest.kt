package com.alraad.app

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.PlaybackRequest
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.contracts.SubtitleTrack
import com.alraad.feature.player.impl.Media3PlayerController
import com.alraad.feature.player.impl.externalSubtitleTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof for T4 subtitles: real VTT/SRT/ASS cues decoded by Media3,
 * track selection on/off, external-file attachment with playback continuity,
 * and runtime header isolation (subtitle requests never carry the stream's
 * credentials). Runs in the emulator lane; Robolectric cannot decode media.
 */
@RunWith(AndroidJUnit4::class)
class SubtitlePlaybackRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext.applicationContext

    private val noopLogger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) = Unit

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }

    private lateinit var server: MockWebServer
    private lateinit var controller: Media3PlayerController

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = subtitleDispatcher()
        server.start()
        controller = Media3PlayerController(context, noopLogger)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { controller.release() }
        server.shutdown()
    }

    @Test
    fun vttCuesRenderDuringPlayback() {
        val cues = playWithSubtitle("subs.vtt", "text/vtt", "Hello from VTT")
        assertTrue("No VTT cue rendered", cues.await(60, TimeUnit.SECONDS))
    }

    @Test
    fun srtCuesRenderDuringPlayback() {
        val cues = playWithSubtitle("subs.srt", "application/x-subrip", "Hello from SRT")
        assertTrue("No SRT cue rendered", cues.await(60, TimeUnit.SECONDS))
    }

    @Test
    fun assCuesRenderDuringPlayback() {
        val cues = playWithSubtitle("subs.ass", "text/x-ssa", "Hello from ASS")
        assertTrue("No ASS cue rendered", cues.await(60, TimeUnit.SECONDS))
    }

    @Test
    fun textTrackSelectionTogglesOffAndOn() {
        playWithSubtitle("subs.vtt", "text/vtt", "Hello from VTT")
        var textTracksEnabled = false
        instrumentation.runOnMainSync {
            val player = checkNotNull(controller.currentPlayer())
            textTracksEnabled = hasEnabledTextTrack(player)
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
        }
        instrumentation.runOnMainSync {
            val player = checkNotNull(controller.currentPlayer())
            assertTrue("Text track should be enabled before the toggle", textTracksEnabled)
            assertTrue(
                "Text track still enabled after disabling",
                !hasEnabledTextTrack(player),
            )
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()
            assertTrue(
                "Text track not re-enabled",
                hasEnabledTextTrack(player),
            )
        }
    }

    @Test
    fun externalSubtitleAttachPreservesPlaybackAndRenders() {
        val cues = playWithSubtitle(null, null, "Hello from VTT")
        var positionBefore = 0L
        var positionAfter = -1L
        instrumentation.runOnMainSync {
            val player = checkNotNull(controller.currentPlayer())
            positionBefore = player.currentPosition
        }
        val track =
            requireNotNull(
                externalSubtitleTrack(server.url("/subs.vtt").toString(), "movie.ar.vtt"),
            )
        instrumentation.runOnMainSync {
            controller.addExternalSubtitle(track)
            positionAfter = checkNotNull(controller.currentPlayer()).currentPosition
        }
        assertEquals(listOf(track), controller.currentPlaybackRequest.value?.subtitles)
        assertTrue("Player was not rebuilt", positionAfter >= 0)
        assertTrue(
            "Position jumped by the attach: before=$positionBefore after=$positionAfter",
            positionAfter in (positionBefore - 1500)..(positionBefore + 1500),
        )
        assertTrue("Attached subtitle never rendered a cue", cues.await(60, TimeUnit.SECONDS))
    }

    @Test
    fun subtitleRequestsNeverCarryStreamCredentials() {
        val cues = playWithSubtitle("subs.vtt", "text/vtt", "Hello from VTT")
        assertTrue(cues.await(60, TimeUnit.SECONDS))
        val requests = mutableListOf<RecordedRequest>()
        while (true) {
            requests += server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
        }
        val videoRequest = requests.first { it.path.orEmpty().startsWith("/video.mp4") }
        val subtitleRequest = requests.first { it.path.orEmpty().startsWith("/subs.vtt") }
        assertEquals("session-secret", videoRequest.getHeader("Cookie"))
        assertNull("Credential leaked to the subtitle request", subtitleRequest.getHeader("Cookie"))
        assertNull(subtitleRequest.getHeader("Authorization"))
        assertEquals("text/vtt", subtitleRequest.getHeader("Accept"))
    }

    /** Plays the fixture with (or without) one subtitle; returns a latch that fires on its cue text. */
    private fun playWithSubtitle(
        subtitlePath: String?,
        mimeType: String?,
        expectedCue: String,
    ): CountDownLatch {
        val cueLatch = CountDownLatch(1)
        val stream =
            StreamUrl(
                url = server.url("/video.mp4").toString(),
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
                headers = mapOf("Cookie" to "session-secret"),
            )
        val subtitles =
            if (subtitlePath != null && mimeType != null) {
                listOf(
                    SubtitleTrack(
                        id = "net:$subtitlePath",
                        url = server.url("/$subtitlePath").toString(),
                        mimeType = mimeType,
                        language = "ar",
                        label = subtitlePath,
                        sourcePageUrl = "",
                        sourceTabId = "",
                        requestHeaders = mapOf("Accept" to mimeType),
                    ),
                )
            } else {
                emptyList()
            }
        val listener =
            object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    if (cueGroup.cues.any { it.text?.contains(expectedCue) == true }) {
                        cueLatch.countDown()
                    }
                }
            }
        instrumentation.runOnMainSync {
            controller.play(PlaybackRequest(sources = listOf(stream), subtitles = subtitles))
            val player = checkNotNull(controller.currentPlayer()) { "Player build failed" }
            player.addListener(listener)
            // Re-seek to the cue window so cues that fired before listener attach re-emit.
            player.seekTo(1)
        }
        return cueLatch
    }

    private fun hasEnabledTextTrack(player: Player): Boolean =
        player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && group.isSelected
        }

    private fun subtitleDispatcher(): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path?.substringBefore('?')) {
                    "/video.mp4" ->
                        MockResponse()
                            .setHeader("Content-Type", "video/mp4")
                            .setHeader("Accept-Ranges", "bytes")
                            .setBody(Buffer().write(videoFixture))
                    "/subs.vtt" -> MockResponse().setHeader("Content-Type", "text/vtt").setBody(VTT_FIXTURE)
                    "/subs.srt" -> MockResponse().setHeader("Content-Type", "application/x-subrip").setBody(SRT_FIXTURE)
                    "/subs.ass" -> MockResponse().setHeader("Content-Type", "text/x-ssa").setBody(ASS_FIXTURE)
                    else -> MockResponse().setResponseCode(404)
                }
        }

    private companion object {
        const val VTT_FIXTURE = "WEBVTT\n\n00:00.000 --> 00:04.000\nHello from VTT\n"

        const val SRT_FIXTURE = "1\n00:00:00,000 --> 00:00:04,000\nHello from SRT\n"

        const val ASS_FIXTURE =
            "[Script Info]\nScriptType: v4.00+\n\n" +
                "[V4+ Styles]\n" +
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour," +
                " Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow," +
                " Alignment, MarginL, MarginR, MarginV, Encoding\n" +
                "Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2," +
                "10,10,10,1\n\n" +
                "[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:00.00,0:00:04.00,Default,,0,0,0,,Hello from ASS\n"

        /** Shared download of the same clip the player-runtime scenario uses. */
        val videoFixture: ByteArray by lazy {
            val fixtureUrl =
                "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
            val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(fixtureUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Test video download failed: HTTP ${response.code}" }
                requireNotNull(response.body).bytes()
            }
        }
    }
}
