package com.alraad.feature.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.feature.browser.tabs.TabManager
import com.alraad.feature.browser.web.PopupBlockingWebChromeClient
import com.alraad.feature.browser.web.SafeWebViewConfig

/**
 * Browser shell: RTL AddressBar on top of a hardened WebView. Navigation from the
 * address bar updates the active tab in TabManager; the WebView loads the active
 * tab's URL. Ad blocking and sniffing come from WebViewIntegrationHelper.
 */
@Composable
fun BrowserScreen(
    tabManager: TabManager,
    webViewIntegration: WebViewIntegrationHelper,
    diagnosticsLogger: DiagnosticsLogger,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val tabState by tabManager.state.collectAsState()
    val activeTab = tabState.tabs.firstOrNull { it.id == tabState.activeTabId }
    var isLoading by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        AddressBar(
            currentUrl = activeTab?.url ?: "",
            isLoading = isLoading,
            onNavigate = { url ->
                val id = tabState.activeTabId ?: tabManager.openTab(url).id
                tabManager.updateTab(id, url = url)
            },
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    SafeWebViewConfig.apply(this, settings)
                    webChromeClient = PopupBlockingWebChromeClient(diagnosticsLogger)
                    webViewClient =
                        webViewIntegration.webViewClient(
                            onPageFinished = { isLoading = false },
                        )
                }
            },
            update = { webView ->
                isLoading = true
                activeTab?.url?.takeIf { it.isNotBlank() && it != webView.url }?.let(webView::loadUrl)
            },
        )
    }
}
