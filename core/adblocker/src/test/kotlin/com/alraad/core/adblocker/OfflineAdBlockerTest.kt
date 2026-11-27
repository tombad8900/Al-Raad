package com.alraad.core.adblocker

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistStore
import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun settingsRepo(enabled: Boolean) =
    object : SettingsRepository {
        override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings(adBlockEnabled = enabled))

        override suspend fun update(settings: AppSettings) = Unit
    }

@RunWith(RobolectricTestRunner::class)
class OfflineAdBlockerTest {
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

    private val blocker =
        OfflineAdBlocker(
            ApplicationProvider.getApplicationContext(),
            noopLogger,
            settingsRepo(enabled = true),
            BlocklistStore(ApplicationProvider.getApplicationContext()),
        )

    @Test fun `blocks listed ad domains and subdomains`() =
        runTest {
            assertTrue(blocker.shouldBlock("https://ad.doubleclick.net/track"))
            assertTrue(blocker.shouldBlock("https://criteo.com/x.js"))
        }

    @Test fun `allows normal sites and malformed urls`() =
        runTest {
            assertFalse(blocker.shouldBlock("https://example.com"))
            assertFalse(blocker.shouldBlock("not a url"))
        }

    @Test fun `whitelist overrides blocklist`() =
        runTest {
            blocker.addToWhitelist("criteo.com")
            assertFalse(blocker.shouldBlock("https://criteo.com/x.js"))
            blocker.removeFromWhitelist("criteo.com")
            assertTrue(blocker.shouldBlock("https://criteo.com/x.js"))
        }

    @Test fun `disabled ad blocking stops all blocking`() =
        runTest {
            val off =
                OfflineAdBlocker(
                    ApplicationProvider.getApplicationContext(),
                    noopLogger,
                    settingsRepo(enabled = false),
                    BlocklistStore(ApplicationProvider.getApplicationContext()),
                )
            assertFalse(off.shouldBlock("https://ad.doubleclick.net/track"))
        }

    @Test fun `cosmetic css hides generic ad selectors`() {
        assertTrue(blocker.cosmeticCss("example.com").contains("display: none"))
    }
}

@RunWith(RobolectricTestRunner::class)
class OfflineAdBlockerConcurrencyTest {
    @Test fun `concurrent whitelist toggles and shouldBlock calls do not race or throw`() =
        runTest {
            val blocker =
                OfflineAdBlocker(
                    ApplicationProvider.getApplicationContext(),
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
                    },
                    settingsRepo(enabled = true),
                    BlocklistStore(ApplicationProvider.getApplicationContext()),
                )
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

            fun worker(body: () -> Unit): Thread =
                Thread {
                    try {
                        body()
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            val writers =
                (1..4).map { w ->
                    worker {
                        repeat(500) {
                            blocker.addToWhitelist("host$it-w$w.example.com")
                            blocker.removeFromWhitelist("host$it-w$w.example.com")
                        }
                    }
                }
            val readers =
                (1..4).map {
                    worker {
                        runBlocking {
                            repeat(500) { blocker.shouldBlock("https://host$it-w1.example.com/x") }
                        }
                    }
                }
            (writers + readers).forEach { it.start() }
            (writers + readers).forEach { it.join(30_000) }
            assertTrue("workers still alive", (writers + readers).none { it.isAlive })
            assertTrue("worker failures: $failures", failures.isEmpty())
        }
}
