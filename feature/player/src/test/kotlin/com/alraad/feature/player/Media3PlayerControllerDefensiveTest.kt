package com.alraad.feature.player

import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.fakes.FakeDiagnosticsLogger
import com.alraad.feature.player.impl.Media3PlayerController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stream handoff to the PLAYER route must happen before any fallible player
 * construction, so a build failure can never leave the route on the no-stream
 * placeholder after a crash (owner real-device finding). Construction failures
 * themselves are asynchronous in ExoPlayer and surface through onPlayerError ->
 * playbackError; the synchronous contract is tested here.
 */
@RunWith(RobolectricTestRunner::class)
class Media3PlayerControllerDefensiveTest {
    private val controller =
        Media3PlayerController(
            org.robolectric.RuntimeEnvironment.getApplication(),
            FakeDiagnosticsLogger(),
        )

    @After fun tearDown() = controller.release()

    @Test
    fun `play hands the stream to the route before building the player`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example/stream.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
                headers = mapOf("Referer" to "https://page.example/"),
            )
        controller.play(stream)
        assertEquals(stream, controller.currentStream.value)
        assertNull(controller.playbackError.value)
        assertNotNull(controller.currentPlayer())
    }

    @Test
    fun `a build failure surfaces as playbackError with the stream still on the route`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example/broken.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
            )
        controller.playerFactory = { throw IllegalStateException("deterministic test failure") }
        controller.play(stream)
        // The route keeps the stream (no placeholder regression), the failure is an
        // in-app error state, and no exception escapes play().
        assertEquals(stream, controller.currentStream.value)
        assertEquals("deterministic test failure", controller.playbackError.value)
        assertNull(controller.currentPlayer())
    }

    @Test
    fun `after a build failure the next play recovers and clears the error`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example/recover.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
            )
        controller.playerFactory = { throw IllegalStateException("deterministic test failure") }
        controller.play(stream)
        controller.playerFactory = { ctx -> androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build() }
        controller.play(stream)
        assertEquals(stream, controller.currentStream.value)
        assertNull(controller.playbackError.value)
        assertNotNull(controller.currentPlayer())
    }

    @Test
    fun `repeating play for the same stream builds the player only once`() {
        val stream =
            StreamUrl(
                url = "https://cdn.example/once.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
            )
        var builds = 0
        controller.playerFactory = { ctx ->
            builds++
            androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build()
        }
        // One tap reaches play() twice: once from stream selection in the nav
        // callback, once from PlayerScreen's DisposableEffect. Exactly one player
        // may be built (owner real-device double-start finding).
        controller.play(stream)
        controller.play(stream)
        assertEquals(1, builds)
        assertEquals(stream, controller.currentStream.value)
        assertNull(controller.playbackError.value)
    }

    @Test
    fun `a subsequent play replaces the stream and clears prior error state`() {
        val first =
            StreamUrl(
                url = "https://cdn.example/a.m3u8",
                format = StreamFormat.HLS,
                source = StreamSource.NETWORK,
            )
        val second =
            StreamUrl(
                url = "https://cdn.example/b.mp4",
                format = StreamFormat.MP4,
                source = StreamSource.NETWORK,
            )
        controller.play(first)
        controller.play(second)
        assertEquals(second, controller.currentStream.value)
        assertNull(controller.playbackError.value)
    }
}
