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
