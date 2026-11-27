package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewIntegrationHelperErrorLoggingTest {
    private val recorded = mutableListOf<DiagEntry>()
    private val logger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) {
                recorded += DiagEntry(0, 0L, category, level, message, details)
            }

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }
    private val allowAll =
        object : AdBlocker {
            override suspend fun shouldBlock(url: String): Boolean = false

            override fun cosmeticCss(host: String): String = ""
        }

    @Test fun `subresource load failures are logged, not dropped`() {
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        helper.logLoadError("https://cdn.example/ad.js", -2, "net::ERR_FAILED", isMainFrame = false)
        val entry = recorded.single { it.details["url"] == "https://cdn.example/ad.js" }
        assertEquals(DiagCategory.WEBVIEW, entry.category)
        assertEquals(DiagLevel.WARN, entry.level)
        assertEquals("false", entry.details["mainFrame"])
    }

    @Test fun `main-frame failures log at ERROR`() {
        val helper = WebViewIntegrationHelper(allowAll, logger, CoroutineScope(Dispatchers.Main))
        helper.logLoadError("https://site.example/", -6, "net::ERR_FILE_NOT_FOUND", isMainFrame = true)
        val entry = recorded.single { it.details["url"] == "https://site.example/" }
        assertEquals(DiagLevel.ERROR, entry.level)
        assertEquals("true", entry.details["mainFrame"])
    }
}
