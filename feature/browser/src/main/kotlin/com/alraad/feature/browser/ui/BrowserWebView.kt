package com.alraad.feature.browser.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.alraad.core.common.contracts.StreamSource
import com.alraad.feature.browser.web.PopupBlockingWebChromeClient
import com.alraad.feature.browser.web.SafeWebViewConfig

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
    val tabs = environment.tabManager
    if (activeTab != null) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { _ ->
                environment.webViewPool.obtain(activeTab.id).apply {
                    url?.let { pageTabAssociator.restoreCurrentPage(activeTab.id, it) }
                    setBackgroundColor(backgroundColor.toArgb())
                    SafeWebViewConfig.apply(this, environment.settings)
                    webChromeClient = PopupBlockingWebChromeClient(environment.diagnosticsLogger)
                    webViewClient =
                        environment.webViewIntegration.webViewClient(
                            onNavigationRequested = { _, url -> pageTabAssociator.recordRequest(activeTab.id, url) },
                            onPageStart = { _, url ->
                                val page = pageTabAssociator.onPageStartedWithContext(url, activeTab.id)
                                page.tabId?.let { id ->
                                    handlers.actions.onPageStarted(id, url, page.startsNewNavigation)
                                    if (tabs.state.value.activeTabId == id) onLoadingChange(true)
                                }
                            },
                            onPageFinished = { webView ->
                                webView.url?.let { url ->
                                    if (url == "about:blank") {
                                        webView.clearHistory()
                                        if (tabs.state.value.activeTabId == activeTab.id) onLoadingChange(false)
                                    } else {
                                        val owner = pageTabAssociator.onPageFinished(url)
                                        if (owner == tabs.state.value.activeTabId) onLoadingChange(false)
                                        owner?.let { handlers.actions.onPageFinished(it, url, webView.title ?: "") }
                                    }
                                }
                            },
                            onRequestObserved = { url, headers, pageUrl ->
                                val tabId = pageTabAssociator.tabIdForPage(pageUrl)
                                environment.sniffer.onRequestObserved(url, headers, null, StreamSource.NETWORK, pageUrl, tabId)
                                environment.subtitleSniffer.onRequestObserved(url, headers, pageUrl, tabId)
                            },
                        )
                }
            },
            update = { webView ->
                if (pageTabAssociator.shouldLoad(activeTab.id, activeTab.url, webView.url)) {
                    pageTabAssociator.recordRequest(activeTab.id, activeTab.url)
                    onLoadingChange(true)
                    webView.loadUrl(activeTab.url)
                }
            },
        )
    }
}
