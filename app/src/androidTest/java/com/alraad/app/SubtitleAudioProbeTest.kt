package com.alraad.app

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DIAGNOSTIC ONLY (instinct/t4-subtitle-diagnostic, not for #83): the same sideloaded
 * subtitle cue assertions as SubtitlePlaybackRuntimeTest, but over an audio-only clip
 * so the runner's starved software h264 decode cannot compete with subtitle extraction.
 * Differential probe for run 46117212581's n=0-forever failures; it does not replace
 * the actual-video acceptance, and a pass here does not exonerate shipped behavior.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleAudioProbeTest {
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

    /**
     * Known-good playback control, no subtitles involved: does the position clock
     * advance at all on this emulator for each media type? READY + isPlaying with a
     * frozen position was observed in run 46121415741; this measures it directly.
     */
    @Test
    fun controlAudioPositionAdvances() = positionControl("audio.wav")

    @Test
    fun controlVideoPositionAdvances() = positionControl("fixture.mp4")

    private fun positionControl(mediaPath: String) {
        val baseUrl =
            checkNotNull(InstrumentationRegistry.getArguments().getString("fixtureBaseUrl")) {
                "Position control requires the host-served fixture (fixtureBaseUrl)"
            }
        val stream =
            StreamUrl(
                url = "$baseUrl/$mediaPath",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
            )
        instrumentation.runOnMainSync {
            controller.play(PlaybackRequest(sources = listOf(stream)))
            val player = checkNotNull(controller.currentPlayer()) { "Player build failed" }
            attachEvidenceListener(player)
            player.playWhenReady = true
        }
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val player = checkNotNull(controller.currentPlayer())
            if (player.playbackState == Player.STATE_READY) {
                ready.countDown()
            } else {
                player.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) ready.countDown()
                        }
                    },
                )
            }
        }
        assertTrue("Player never became READY for $mediaPath", ready.await(60, TimeUnit.SECONDS))
        val p0 = readPosition()
        Thread.sleep(10_000)
        val p1 = readPosition()
        Log.i(TAG, "control $mediaPath pos0=" + p0 + " pos1=" + p1 + " delta=" + (p1 - p0) + "ms")
        assertTrue(
            "Position frozen on $mediaPath: pos0=" + p0 + " pos1=" + p1,
            p1 - p0 > 2_000,
        )
    }

    private fun readPosition(): Long {
        var pos = -1L
        instrumentation.runOnMainSync { pos = controller.currentPlayer()?.currentPosition ?: -1L }
        return pos
    }

    @Test
    fun vttCuesOnAudioOnly() = probe("subs.vtt", "text/vtt", "Hello from VTT")

    @Test
    fun srtCuesOnAudioOnly() = probe("subs.srt", "application/x-subrip", "Hello from SRT")

    @Test
    fun assCuesOnAudioOnly() = probe("subs.ass", "text/x-ssa", "Hello from ASS")

    private fun probe(
        subtitlePath: String,
        mimeType: String,
        expectedCue: String,
    ) {
        val baseUrl =
            checkNotNull(InstrumentationRegistry.getArguments().getString("fixtureBaseUrl")) {
                "Audio probe requires the host-served fixture (fixtureBaseUrl)"
            }
        val stream =
            StreamUrl(
                url = "$baseUrl/audio.wav",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
                headers = mapOf("Cookie" to "session-secret"),
            )
        val subtitle =
            SubtitleTrack(
                id = "net:$subtitlePath",
                url = server.url("/$subtitlePath").toString(),
                mimeType = mimeType,
                language = "ar",
                label = subtitlePath,
                sourcePageUrl = "",
                sourceTabId = "",
                requestHeaders = mapOf("Accept" to mimeType),
            )
        val cueLatch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            controller.play(PlaybackRequest(sources = listOf(stream), subtitles = listOf(subtitle)))
            val player = checkNotNull(controller.currentPlayer()) { "Player build failed" }
            attachEvidenceListener(player)
            player.addListener(
                object : Player.Listener {
                    override fun onCues(cueGroup: CueGroup) {
                        if (cueGroup.cues.any { it.text?.contains(expectedCue) == true }) {
                            cueLatch.countDown()
                        }
                    }
                },
            )
            player.trackSelectionParameters =
                player.trackSelectionParameters.buildUpon().setPreferredTextLanguage("ar").build()
        }
        // Same bounded re-seek/replay structure as the video lane: deterministic in
        // structure; audio plays back in realtime, so a healthy pipeline emits the
        // 0-4s cue on the first pass.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline && cueLatch.count > 0) {
            instrumentation.runOnMainSync {
                val player = controller.currentPlayer() ?: return@runOnMainSync
                player.seekTo(CUE_WINDOW_MS)
                player.playWhenReady = true
            }
            cueLatch.await(5, TimeUnit.SECONDS)
        }
        instrumentation.runOnMainSync { controller.currentPlayer()?.pause() }
        assertTrue("No cue rendered on audio-only media for $subtitlePath", cueLatch.count == 0L)
    }

    private fun attachEvidenceListener(player: Player) {
        if (player is ExoPlayer) {
            player.addAnalyticsListener(EventLogger(TAG))
        }
        player.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    Log.i(
                        TAG,
                        "tracks textGroups=" + textGroups.size +
                            " selected=" + textGroups.count { it.isSelected } +
                            " t=" + System.currentTimeMillis(),
                    )
                }

                override fun onCues(cueGroup: CueGroup) {
                    Log.i(
                        TAG,
                        "cues n=" + cueGroup.cues.size +
                            " pos=" + player.currentPosition + "ms t=" + System.currentTimeMillis(),
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.i(TAG, "playerError " + error.errorCodeName + " t=" + System.currentTimeMillis())
                }
            },
        )
    }

    private fun subtitleDispatcher(): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response =
                    when (request.path?.substringBefore('?')) {
                        "/subs.vtt" ->
                            MockResponse().setHeader("Content-Type", "text/vtt").setBody(VTT_FIXTURE)
                        "/subs.srt" ->
                            MockResponse()
                                .setHeader("Content-Type", "application/x-subrip")
                                .setBody(SRT_FIXTURE)
                        "/subs.ass" ->
                            MockResponse().setHeader("Content-Type", "text/x-ssa").setBody(ASS_FIXTURE)
                        else -> MockResponse().setResponseCode(404)
                    }
                Log.i(
                    TAG,
                    "serve path=" + request.path + " status=" + response.status.split(' ')[1] +
                        " t=" + System.currentTimeMillis(),
                )
                return response
            }
        }

    private companion object {
        const val TAG: String = "SubtitleProof"
        const val CUE_WINDOW_MS = 2_000L
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
    }
}
