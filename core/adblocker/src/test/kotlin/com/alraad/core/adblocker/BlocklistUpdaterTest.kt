package com.alraad.core.adblocker

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistStore
import com.alraad.core.adblocker.impl.BlocklistUpdater
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val LIST_ID = "easylist"
private const val LISTED_HOST = "tracker.t6-acceptance-test.example"

@RunWith(RobolectricTestRunner::class)
class BlocklistUpdaterTest {
    private val warnings = mutableListOf<Map<String, String>>()
    private val logger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) {
                if (category == DiagCategory.ADBLOCK && level == DiagLevel.WARN) warnings += details
            }

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }
    private val settings =
        object : SettingsRepository {
            override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings(adBlockEnabled = true))

            override suspend fun update(settings: AppSettings) = Unit
        }
    private val store = BlocklistStore(ApplicationProvider.getApplicationContext()).also { it.minDomains = 1 }
    private val updater = BlocklistUpdater(store, logger)

    private fun onlyListEnabled() {
        com.alraad.core.adblocker.impl.BlocklistCatalog.sources.forEach { store.setEnabled(it.id, it.id == LIST_ID) }
    }

    @Test fun `successful update promotes the list`() =
        runTest {
            onlyListEnabled()
            updater.fetcher = { "||$LISTED_HOST^\n" }
            val result = updater.updateAll()
            assertEquals(listOf(LIST_ID), result.updated)
            assertEquals(setOf(LISTED_HOST), store.storedDomains(LIST_ID))
        }

    @Test fun `failed truncated and malformed downloads keep the previous snapshot and warn`() =
        runTest {
            onlyListEnabled()
            updater.fetcher = { "||$LISTED_HOST^\n" }
            updater.updateAll()
            val failures: List<(String) -> String> =
                listOf(
                    { error("HTTP 503") },
                    { "" },
                    { "!comment only\n[Adblock Plus 2.0]\n" },
                )
            for (failure in failures) {
                warnings.clear()
                updater.fetcher = failure
                val result = updater.updateAll()
                assertEquals(listOf(LIST_ID), result.keptLastGood)
                assertTrue(result.updated.isEmpty())
                assertEquals(setOf(LISTED_HOST), store.storedDomains(LIST_ID))
                assertEquals(listOf(mapOf("list" to LIST_ID)), warnings)
            }
        }

    @Test fun `a list with only cosmetic and option rules is reported as unsupported, not as a failed update`() =
        runTest {
            onlyListEnabled()
            updater.fetcher = { "example.com##.ad\nsite.example.org##+js(trusted-click-element, #x)\n||fb.example.net^${'$'}third-party\n" }
            val result = updater.updateAll()
            assertEquals(listOf(LIST_ID), result.unsupportedOnly)
            assertTrue(result.updated.isEmpty() && result.keptLastGood.isEmpty())
            assertTrue(warnings.isEmpty())
        }

    @Test fun `disabled lists are not fetched`() =
        runTest {
            com.alraad.core.adblocker.impl.BlocklistCatalog.sources.forEach { store.setEnabled(it.id, false) }
            var calls = 0
            updater.fetcher = {
                calls++
                "||$LISTED_HOST^\n"
            }
            val result = updater.updateAll()
            assertEquals(0, calls)
            assertTrue(result.updated.isEmpty() && result.keptLastGood.isEmpty())
            assertNull(store.storedDomains(LIST_ID))
        }

    @Test fun `per-list toggle takes effect after reload without restart`() =
        runTest {
            onlyListEnabled()
            updater.fetcher = { "||$LISTED_HOST^\n" }
            updater.updateAll()
            val blocker =
                OfflineAdBlocker(ApplicationProvider.getApplicationContext(), logger, settings, store)
            assertTrue(blocker.shouldBlock("https://$LISTED_HOST/x"))
            store.setEnabled(LIST_ID, false)
            blocker.reload()
            assertFalse(blocker.shouldBlock("https://$LISTED_HOST/x"))
            store.setEnabled(LIST_ID, true)
            blocker.reload()
            assertTrue(blocker.shouldBlock("https://$LISTED_HOST/x"))
        }

    @Test fun `html error page served with 200 does not replace the last good list`() =
        runTest {
            store.minDomains = null
            onlyListEnabled()
            val good = (1..150).joinToString("\n") { "||host$it.t6-acceptance-test.example^" }
            updater.fetcher = { good }
            updater.updateAll()
            val html =
                "<!DOCTYPE html>\n<html>\n<head>\n<title>Network login required</title>\n" +
                    "<meta charset=\"utf-8\">\n</head>\n<body>\n<h1>Sign in to continue</h1>\n" +
                    "<p>Please accept the terms to use this Wi-Fi network.</p>\n<p>Contact support</p>\n" +
                    "<form action=\"/login\" method=\"post\">\n<input name=\"user\">\n</form>\n" +
                    (1..200).joinToString("\n") { "<div class=\"row$it\">Item $it of the page</div>" } + "\n</body>\n</html>\n"
            updater.fetcher = { html }
            val result = updater.updateAll()
            assertEquals(listOf(LIST_ID), result.keptLastGood)
            assertEquals(150, store.storedDomains(LIST_ID)?.size)
        }
}
