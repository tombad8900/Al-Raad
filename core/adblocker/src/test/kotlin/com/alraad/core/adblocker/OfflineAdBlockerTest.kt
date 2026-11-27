package com.alraad.core.adblocker

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    private val blocker = OfflineAdBlocker(ApplicationProvider.getApplicationContext(), noopLogger)

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
                )
            val writers =
                (1..4).map { w ->
                    Thread {
                        repeat(500) {
                            blocker.addToWhitelist("host$it-w$w.example.com")
                            blocker.removeFromWhitelist("host$it-w$w.example.com")
                        }
                    }
                }
            val readers =
                (1..4).map {
                    Thread {
                        runBlocking {
                            repeat(500) { blocker.shouldBlock("https://host$it-w1.example.com/x") }
                        }
                    }
                }
            (writers + readers).forEach { it.start() }
            (writers + readers).forEach { it.join(30_000) }
            assertTrue((writers + readers).none { it.isAlive })
        }
}
