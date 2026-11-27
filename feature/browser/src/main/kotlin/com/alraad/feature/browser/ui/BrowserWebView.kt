package com.alraad.feature.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.alraad.core.common.contracts.StreamSource
import com.alraad.feature.browser.web.PopupBlockingWebChromeClient
import com.alraad.feature.browser.web.SafeWebViewConfig

/** The browser's WebView surface: hardened config, ad-block/sniffer client, tab-bound loads. */
@Composable
fun BrowserWebView(
    activeTab: com.alraad.feature.browser.tabs.BrowserTab?,
    environment: BrowserWebViewEnvironment,
    backgroundColor: Color,
    handlers: BrowserHandlers,
    pageTabAssociator: PageTabAssociator,
    onLoadingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabManager = environment.tabManager
    val settings = environment.settings
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(backgroundColor.toArgb())
                SafeWebViewConfig.apply(this, settings)
                webChromeClient = PopupBlockingWebChromeClient(environment.diagnosticsLogger)
                webViewClient =
                    environment.webViewIntegration.webViewClient(
                        onPageStarted = { _, url ->
                            val ownerTabId = pageTabAssociator.onPageStarted(url, tabManager.state.value.activeTabId)
                            if (ownerTabId != null && ownerTabId == tabManager.state.value.activeTabId) {
                                onLoadingChange(true)
                            }
                        },
                        onPageFinishedWithUrl = { webView, loadedUrl ->
                            val ownerTabId = pageTabAssociator.onPageFinished(loadedUrl)
                            if (ownerTabId == tabManager.state.value.activeTabId) onLoadingChange(false)
                            ownerTabId?.let {
                                handlers.actions.onPageFinished(it, loadedUrl, webView.title ?: "")
                            }
                        },
                        onRequestObserved = { url, headers, pageUrl ->
                            environment.sniffer.onRequestObserved(url, headers, null, StreamSource.NETWORK, pageUrl)
                        },
                    )
            }
        },
        update = { webView ->
            activeTab?.let { tab ->
                if (pageTabAssociator.shouldLoad(tab.id, tab.url, webView.url)) {
                    pageTabAssociator.recordRequest(tab.id, tab.url)
                    onLoadingChange(true)
                    webView.loadUrl(tab.url)
                }
            }
        },
    )
}
