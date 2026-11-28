package com.alraad.core.adblocker

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistStore
import com.alraad.core.adblocker.impl.EngineStatus
import com.alraad.core.adblocker.impl.NativeAdblock
import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngineWarmUpTest {
    private val logger =
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

    private fun settings(enabled: Boolean) =
        object : SettingsRepository {
            override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings(adBlockEnabled = enabled))

            override suspend fun update(settings: AppSettings) = Unit
        }

    private fun blocker(enabled: Boolean = true): OfflineAdBlocker {
        val store = BlocklistStore(ApplicationProvider.getApplicationContext()).also { it.minDomains = 1 }
        check(store.promote("easylist", "||tracker.example^"))
        return OfflineAdBlocker(ApplicationProvider.getApplicationContext(), logger, settings(enabled), store)
    }

    private fun requireNative() {
        assumeTrue(NativeAdblock.isAvailable() || System.getenv("CI") == null)
        assertTrue("native library missing in CI", NativeAdblock.isAvailable())
    }

    @Test fun `warm up builds the engine before any request`() =
        runTest {
            requireNative()
            val blocker = blocker()
            assertEquals(EngineStatus.NOT_BUILT, blocker.engineStatus)
            blocker.warmUp()
            assertEquals(EngineStatus.READY, blocker.engineStatus)
            assertTrue(blocker.shouldBlock("https://tracker.example/a.js", "https://news.example/", "script"))
        }

    @Test fun `a request racing the warm up is still checked by the finished engine`() =
        runTest {
            requireNative()
            val blocker = blocker()
            val warm = async { blocker.warmUp() }
            val racing = async { blocker.shouldBlock("https://tracker.example/a.js", "https://news.example/", "script") }
            assertTrue("request passed unchecked while the engine built", racing.await())
            warm.await()
            assertEquals(EngineStatus.READY, blocker.engineStatus)
        }

    @Test fun `warm up does not build the engine while blocking is off`() =
        runTest {
            requireNative()
            val blocker = blocker(enabled = false)
            blocker.warmUp()
            assertEquals(EngineStatus.NOT_BUILT, blocker.engineStatus)
        }
}
