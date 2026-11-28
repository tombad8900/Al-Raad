package com.alraad.core.adblocker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistStore
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
class RotanaRegressionTest {
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

    private val store by lazy {
        BlocklistStore(ApplicationProvider.getApplicationContext()).also { it.minDomains = 1 }
    }

    private fun newBlocker() =
        OfflineAdBlocker(ApplicationProvider.getApplicationContext(), logger, settings, store)

    private fun requireNative() {
        // Match RuleEngineTest locally, but make a missing host library a hard CI failure.
        if (System.getenv("CI") == null) assumeTrue(NativeAdblock.isAvailable())
        assertTrue("native library missing in CI", NativeAdblock.isAvailable())
    }

    private data class FixtureRequest(
        val initiator: String,
        val url: String,
    ) {
        val type: String
            get() =
                when (initiator) {
                    "link" -> "stylesheet"
                    "script" -> "script"
                    "img" -> "image"
                    "xmlhttprequest", "fetch" -> "xhr"
                    else -> "other"
                }
    }

    private fun loadFixture(): List<FixtureRequest> =
        javaClass.getResourceAsStream("/rotana-urls.txt")!!
            .bufferedReader()
            .useLines { lines ->
                lines.filter { it.isNotBlank() }.map { line ->
                    val fields = line.trim().split(Regex("\\s+"), limit = 2)
                    require(fields.size == 2) { "Invalid Rotana fixture line: $line" }
                    FixtureRequest(fields[0], fields[1])
                }.toList()
            }

    @Test
    fun `Rotana first-party resources and Google search stay allowed while fixture trackers are blocked`() =
        runTest {
            requireNative()
            check(
                store.promote(
                    "easylist",
                    listOf(
                        "||googletagmanager.com^",
                        "||analytics.google.com^",
                        "||stats.g.doubleclick.net^",
                        "||google.com/measurement/conversion",
                        "||google.com.kw/ads/ga-audiences",
                        "||example-ads.test^",
                        "/banner-ad/",
                    ).joinToString("\\n"),
                ),
            )

            val blocker = newBlocker()
            val pageUrl = "https://rotana.net/ar"
            val fixture = loadFixture()
            val trackingUrls =
                setOf(
                    "https://www.googletagmanager.com/gtag/js",
                    "https://www.google.com/measurement/conversion",
                    "https://analytics.google.com/g/collect",
                    "https://stats.g.doubleclick.net/g/collect",
                    "https://www.google.com.kw/ads/ga-audiences",
                )
            val fixtureByUrl = fixture.associateBy { it.url }

            trackingUrls.forEach { url ->
                assertTrue("Expected tracking URL missing from fixture: $url", url in fixtureByUrl)
                val request = fixtureByUrl.getValue(url)
                assertTrue("Expected tracker to be blocked: $url", blocker.shouldBlock(url, pageUrl, request.type))
            }

            val firstPartyAndStaticHosts =
                setOf(
                    "d1rjxhevrfxjk0.cloudfront.net",
                    "cdnjs.cloudflare.com",
                    "rotana.net",
                    "gstatic.com",
                    "www.gstatic.com",
                )
            val allowedRequests =
                fixture.filter { request ->
                    val host = java.net.URI(request.url).host.lowercase()
                    host in firstPartyAndStaticHosts
                }
            assertTrue("Expected CloudFront fixture resources", allowedRequests.any { it.url.contains("cloudfront.net") })
            assertTrue("Expected cdnjs fixture resource", allowedRequests.any { it.url.contains("cdnjs.cloudflare.com") })
            assertTrue("Expected Rotana fixture resource", allowedRequests.any { it.url.contains("rotana.net") })
            allowedRequests.forEach { request ->
                assertFalse(
                    "First-party/static resource was blocked: ${request.url}",
                    blocker.shouldBlock(request.url, pageUrl, request.type),
                )
            }

            assertFalse(
                "Google search navigation was blocked",
                blocker.shouldBlock("https://www.google.com/search?q=test", "https://www.google.com/", "document"),
            )
            assertFalse(
                "Google home navigation was blocked",
                blocker.shouldBlock("https://www.google.com/", "https://www.google.com/", "document"),
            )
            assertFalse(
                "Google Kuwait search navigation was blocked",
                blocker.shouldBlock("https://www.google.com.kw/search?q=x", "https://www.google.com/", "document"),
            )

            val blockedFixtureUrls =
                fixture.filter { request -> blocker.shouldBlock(request.url, pageUrl, request.type) }.map { it.url }.toSet()
            assertEquals("Only the explicitly pinned tracking requests may be blocked", trackingUrls, blockedFixtureUrls)
            assertEquals("Blocked fixture count must equal asserted tracker count", trackingUrls.size, blockedFixtureUrls.size)
        }
}
