package com.alraad.feature.player

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.player.impl.Media3PlayerController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
