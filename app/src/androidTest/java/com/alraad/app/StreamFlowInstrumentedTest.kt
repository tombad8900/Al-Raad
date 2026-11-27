package com.alraad.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SavedStream
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.data.db.AlRaadDatabase
import com.alraad.data.repo.RoomSavedStreamsRepository
import com.alraad.feature.player.impl.Media3PlayerController
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end instrumentation for #10: sniffer -> save -> library -> player.
 * A detected StreamUrl (with verbatim headers) round-trips through the Room
 * library unchanged, then Media3 playback against a local MockWebServer proves
 * the player sends exactly those headers on the media request. Runs on the
 * emulator job once merged to main; not a unit test.
 */
class StreamFlowInstrumentedTest {
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

    @Test fun savedStreamHeadersSurviveRoundTripAndReachPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AlRaadDatabase::class.java).build()
        val repo = RoomSavedStreamsRepository(db.savedStreamDao())
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-ENDLIST\n"),
        )
        server.start()
        try {
            val headers = mapOf("Referer" to "https://source.example/page", "Cookie" to "session=abc; x=y")
            val detected = StreamUrl(server.url("/master.m3u8").toString(), StreamFormat.HLS, StreamSource.NETWORK, headers)

            // Save exactly as the browser's Save action does, then read back as the library does.
            runBlocking {
                repo.save(SavedStream(title = "e2e", stream = detected, createdAt = 1L))
                val restored = repo.observeStreams().first().single().stream
                assertEquals("headers changed in library round-trip", headers, restored.headers)
                assertEquals(detected.url, restored.url)

                // Play as PlayerScreen does; the playlist request must carry the same headers.
                val controller = Media3PlayerController(context, noopLogger)
                controller.play(restored)
                val recorded = withTimeout(15_000) { server.takeRequest(15, TimeUnit.SECONDS) }!!
                assertEquals(headers["Referer"], recorded.getHeader("Referer"))
                assertEquals(headers["Cookie"], recorded.getHeader("Cookie"))
                controller.release()
            }
            assertTrue(true)
        } finally {
            server.shutdown()
            db.close()
        }
    }
}
