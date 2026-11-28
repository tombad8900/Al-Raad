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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    private val store by lazy { BlocklistStore(ApplicationProvider.getApplicationContext()).also { it.minDomains = 1 } }

    private fun newBlocker() = OfflineAdBlocker(ApplicationProvider.getApplicationContext(), logger, settings, store)

    private fun requireNative() {
        // Local runs without the host build skip; in CI a missing library fails the test instead of skipping it.
        if (System.getenv("CI") == null) assumeTrue(NativeAdblock.isAvailable())
        assertTrue("native library missing in CI", NativeAdblock.isAvailable())
    }

    private suspend fun OfflineAdBlocker.blocks(
        url: String,
        source: String = "https://news.example/",
        type: String = "script",
    ) = shouldBlock(url, source, type)

    @Test fun `native library loads and creates an engine`() {
        requireNative()
        val handle = NativeAdblock.nativeCreate("||ads.example^\n")
        assertTrue(handle != 0L)
        NativeAdblock.nativeDestroy(handle)
    }

    @Test fun `stored lists drive path, third-party, exception and badfilter rules`() =
        runTest {
            requireNative()
            check(
                store.promote(
                    "easylist",
                    listOf(
                        "||tracker-only-in-list.example^",
                        "/banner/*\$image,third-party",
                        "/adserver/*",
                        "||allowed.example^",
                        "@@||allowed.example^\$script",
                        "||undone.example^",
                        "||undone.example^\$badfilter",
                    ).joinToString("\n"),
                ),
            )
            val blocker = newBlocker()
            assertTrue(blocker.blocks("https://tracker-only-in-list.example/x.js"))
            assertTrue(blocker.blocks("https://cdn.other.example/banner/a.png", type = "image"))
            assertFalse(blocker.blocks("https://news.example/banner/a.png", type = "image"))
            assertTrue(blocker.blocks("https://news.example/adserver/x.js"))
            assertFalse(blocker.blocks("https://allowed.example/x.js"))
            assertTrue(blocker.blocks("https://allowed.example/p.png", type = "image"))
            assertFalse(blocker.blocks("https://undone.example/x.js"))
            assertFalse(blocker.blocks("https://news.example/app.js"))
            assertEquals(EngineStatus.READY, blocker.engineStatus)
            blocker.addToWhitelist("tracker-only-in-list.example")
            assertFalse(blocker.blocks("https://tracker-only-in-list.example/x.js"))
        }

    @Test fun `reload and list toggle rebuild the engine`() =
        runTest {
            requireNative()
            check(store.promote("easylist", "||first-rule.example^"))
            val blocker = newBlocker()
            assertTrue(blocker.blocks("https://first-rule.example/x.js"))
            check(store.promote("easylist", "||second-rule.example^"))
            assertFalse(blocker.blocks("https://second-rule.example/x.js"))
            blocker.reload()
            assertTrue(blocker.blocks("https://second-rule.example/x.js"))
            assertFalse(blocker.blocks("https://first-rule.example/x.js"))
            store.setEnabled("easylist", false)
            blocker.reload()
            assertFalse(blocker.blocks("https://second-rule.example/x.js"))
            store.setEnabled("easylist", true)
            blocker.reload()
            assertTrue(blocker.blocks("https://second-rule.example/x.js"))
        }

    @Test fun `a failed rebuild keeps the last known good engine`() =
        runTest {
            requireNative()
            check(store.promote("easylist", "||good-rule.example^"))
            val blocker = newBlocker()
            assertTrue(blocker.blocks("https://good-rule.example/x.js"))
            blocker.engineFactory = { 0L }
            check(store.promote("easylist", "||other-rule.example^"))
            blocker.reload()
            assertTrue(blocker.blocks("https://good-rule.example/x.js"))
            assertFalse(blocker.blocks("https://other-rule.example/x.js"))
            assertEquals(EngineStatus.BUILD_FAILED, blocker.engineStatus)
        }

    @Test fun `queries stay safe while the engine is rebuilt and destroyed concurrently`() {
        requireNative()
        check(store.promote("easylist", "||stable-rule.example^"))
        val blocker = newBlocker()
        runBlocking {
            assertTrue(blocker.blocks("https://stable-rule.example/x.js"))
            val results =
                withContext(Dispatchers.Default) {
                    val readers = (1..6).map { async { (1..400).map { blocker.blocks("https://stable-rule.example/x.js") } } }
                    val rebuilder = async { repeat(40) { blocker.reload() } }
                    rebuilder.await()
                    readers.awaitAll().flatten()
                }
            assertTrue("every query during rebuilds must still see the rule", results.all { it })
        }
    }

    @Test fun `upgrade fixture keeps lossy legacy ABP domains out of the engine but trusts hosts-format ones`() =
        runTest {
            requireNative()
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val dir = java.io.File(context.filesDir, "blocklists").apply { mkdirs() }
            // Pre-raw install: easylist cache once derived from a third-party-only rule on a normal site.
            java.io.File(dir, "easylist.domains").writeText("google.com\nlegacy-ad.example.com")
            java.io.File(dir, "stevenblack-hosts.domains").writeText("legacy-hosts-entry.example.com")
            val blocker = newBlocker()
            assertFalse(blocker.blocks("https://www.google.com/search", source = "https://www.google.com/", type = "document"))
            assertFalse(blocker.blocks("https://legacy-ad.example.com/x.js"))
            assertTrue(blocker.blocks("https://legacy-hosts-entry.example.com/x.js"))
            assertEquals(listOf("easylist"), store.listsPendingRefresh())
            // A fresh download supersedes the lossy cache.
            check(store.promote("easylist", "||fresh-ad.example.com^"))
            blocker.reload()
            assertTrue(blocker.blocks("https://fresh-ad.example.com/x.js"))
            assertFalse(blocker.blocks("https://www.google.com/search", source = "https://www.google.com/", type = "document"))
            assertEquals(emptyList<String>(), store.listsPendingRefresh())
        }

    @Test fun `hosts-format raw lists are converted for the engine`() =
        runTest {
            requireNative()
            check(store.promote("stevenblack-hosts", "127.0.0.1 localhost\n0.0.0.0 hostsfmt-ad.example.com\n"))
            val blocker = newBlocker()
            assertTrue(blocker.blocks("https://hostsfmt-ad.example.com/x.js"))
            assertFalse(blocker.blocks("https://localhost/x.js"))
        }

    @Test fun `without the native engine only the bundled hosts apply and the status says so`() =
        runTest {
            check(store.promote("easylist", "||tracker-only-in-list.example^"))
            val blocker = newBlocker()
            blocker.nativeAvailable = { false }
            assertTrue(blocker.blocks("https://doubleclick.net/x.js"))
            assertFalse(blocker.blocks("https://tracker-only-in-list.example/x.js"))
            assertEquals(EngineStatus.NATIVE_UNAVAILABLE, blocker.engineStatus)
        }
}
