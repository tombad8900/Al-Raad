package com.alraad.feature.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamSource
import com.alraad.feature.browser.sniffer.StreamSniffer
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
    sniffer: StreamSniffer,
    settings: AppSettings,
    modifier: Modifier = Modifier,
    handlers: BrowserHandlers = BrowserHandlers(),
) {
    val tabState by tabManager.state.collectAsState()
    val activeTab = tabState.tabs.firstOrNull { it.id == tabState.activeTabId }
    var isLoading by remember { mutableStateOf(false) }
    val backgroundColor = MaterialTheme.colorScheme.background

    // Open the homepage tab on first launch so the WebView is never left blank.
    LaunchedEffect(Unit) {
        if (tabState.tabs.isEmpty()) tabManager.openTab(settings.homepageUrl)
    }

    Column(modifier = modifier.fillMaxSize()) {
        AddressBar(
            currentUrl = activeTab?.url ?: "",
            isLoading = isLoading,
            onNavigate = { url ->
                val id = tabState.activeTabId ?: tabManager.openTab(url).id
                tabManager.updateTab(id, url = url)
            },
            onBookmarkClick = handlers.actions.onBookmark,
        )
        // Minimal selected-stream handoff into the PLAYER route (richer picker is
        // queued post-MVP): plays the most recent detected stream with its headers.
        handlers.detectedStreams.streams.firstOrNull()?.let { stream ->
            androidx.compose.material3.TextButton(onClick = { handlers.detectedStreams.onPlay(stream) }) {
                androidx.compose.material3.Text(text = "▶ " + (android.net.Uri.parse(stream.url).host ?: stream.url))
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    // Match the app background so an empty WebView is never a white screen.
                    setBackgroundColor(backgroundColor.toArgb())
                    SafeWebViewConfig.apply(this, settings)
                    webChromeClient = PopupBlockingWebChromeClient(diagnosticsLogger)
                    webViewClient =
                        webViewIntegration.webViewClient(
                            onPageFinished = { webView ->
                                isLoading = false
                                webView.url?.let { loaded -> handlers.actions.onPageVisited(loaded, webView.title ?: "") }
                            },
                            onRequestObserved = { url, headers, pageUrl ->
                                sniffer.onRequestObserved(url, headers, null, StreamSource.NETWORK, pageUrl)
                            },
                        )
                }
            },
            update = { webView ->
                activeTab?.url?.takeIf { it.isNotBlank() && it != webView.url }?.let {
                    isLoading = true
                    webView.loadUrl(it)
                }
            },
        )
    }
}
