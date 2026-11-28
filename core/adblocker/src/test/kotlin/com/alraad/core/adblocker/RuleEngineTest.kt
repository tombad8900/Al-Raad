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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuleEngineTest {
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

    private val settings =
        object : SettingsRepository {
            override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings(adBlockEnabled = true))

            override suspend fun update(settings: AppSettings) = Unit
        }

    private fun blockerWithRules(rules: String): OfflineAdBlocker {
        val store = BlocklistStore(ApplicationProvider.getApplicationContext())
        store.minDomains = 1
        check(store.promote("easylist", rules))
        return OfflineAdBlocker(ApplicationProvider.getApplicationContext(), logger, settings, store)
    }

    @Test fun `without the native engine only the bundled hosts apply and the status says so`() =
        runTest {
            assumeTrue(!NativeAdblock.isAvailable())
            val blocker = blockerWithRules("||tracker-only-in-list.example^\n")
            assertTrue(blocker.shouldBlock("https://doubleclick.net/x.js", "https://news.example/", "script"))
            assertFalse(blocker.shouldBlock("https://tracker-only-in-list.example/x.js", "https://news.example/", "script"))
            assertEquals(EngineStatus.NATIVE_UNAVAILABLE, blocker.engineStatus)
        }

    @Test fun `with the native engine list rules and context options are applied`() =
        runTest {
            assumeTrue(NativeAdblock.isAvailable())
            val blocker = blockerWithRules("||tracker-only-in-list.example^\n/banner/*\$image,third-party\n")
            assertTrue(blocker.shouldBlock("https://tracker-only-in-list.example/x.js", "https://news.example/", "script"))
            assertTrue(blocker.shouldBlock("https://cdn.other.example/banner/a.png", "https://news.example/", "image"))
            assertFalse(blocker.shouldBlock("https://news.example/banner/a.png", "https://news.example/", "image"))
            assertFalse(blocker.shouldBlock("https://news.example/app.js", "https://news.example/", "script"))
            assertEquals(EngineStatus.READY, blocker.engineStatus)
        }
}
