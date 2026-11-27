package com.alraad.feature.browser

import android.os.Message
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.feature.browser.web.PopupBlockingWebChromeClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PopupBlockingWebChromeClientTest {
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

    @Test fun `rejects popup creation with and without user gesture`() {
        val client = PopupBlockingWebChromeClient(noopLogger)
        val webView = WebView(ApplicationProvider.getApplicationContext())
        assertFalse(client.onCreateWindow(webView, false, false, Message.obtain()))
        assertFalse(client.onCreateWindow(webView, false, true, Message.obtain()))
        assertFalse(client.onCreateWindow(webView, true, true, Message.obtain()))
    }
}
