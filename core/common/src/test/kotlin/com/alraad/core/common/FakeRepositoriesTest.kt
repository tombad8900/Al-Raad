package com.alraad.core.common

import com.alraad.core.common.contracts.SavedStream
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.fakes.FakePlayerController
import com.alraad.core.common.fakes.FakeSavedStreamsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRepositoriesTest {
    private fun stream(headers: Map<String, String>) =
        SavedStream(
            title = "قناة تجريبية",
            stream =
                StreamUrl(
                    url = "https://cdn.test/live.m3u8?token=t1",
                    format = StreamFormat.HLS,
                    source = StreamSource.NETWORK,
                    headers = headers,
                ),
            thumbnailUrl = "https://cdn.test/thumb.jpg",
            sourcePageUrl = "https://source.test/watch",
            createdAt = 1_700_000_000_000L,
        )

    @Test
    fun `saved stream keeps full headers through the repository`() =
        runTest {
            val repo = FakeSavedStreamsRepository()
            val headers = mapOf("User-Agent" to "UA", "Referer" to "https://source.test/", "Cookie" to "a=b")
            val id = repo.save(stream(headers))

            val stored = repo.getById(id)
            assertEquals(headers, stored?.stream?.headers)
            assertEquals("https://source.test/watch", stored?.sourcePageUrl)
            assertEquals(1, repo.observeStreams().first().size)
        }

    @Test
    fun `delete removes the stream`() =
        runTest {
            val repo = FakeSavedStreamsRepository()
            val id = repo.save(stream(emptyMap()))
            repo.delete(id)
            assertNull(repo.getById(id))
            assertTrue(repo.observeStreams().first().isEmpty())
        }

    @Test
    fun `player controller fake records play with headers intact`() {
        val controller = FakePlayerController()
        val headers = mapOf("Authorization" to "Bearer raw-token")
        controller.play(StreamUrl("https://cdn.test/a.mp4", StreamFormat.MP4, StreamSource.DOM, headers))
        controller.seekTo(5_000L)
        controller.pause()
        controller.release()

        assertEquals("Bearer raw-token", controller.playedStreams[0].headers["Authorization"])
        assertEquals(listOf(5_000L), controller.seekPositionsMs)
        assertEquals(1, controller.pauseCount)
        assertEquals(1, controller.releaseCount)
    }
}
