package com.alraad.feature.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.alraad.core.common.contracts.StreamSource
import com.alraad.feature.browser.sniffer.FetchXhrCaptureScript
import com.alraad.feature.browser.sniffer.SnifferJsBridge
import com.alraad.feature.browser.web.PopupBlockingWebChromeClient
import com.alraad.feature.browser.web.SafeWebViewConfig

private const val SNIFFER_BRIDGE_NAME = "AlRaadSniffer"

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
    val jsBridge = remember(environment.sniffer) { SnifferJsBridge(environment.sniffer) }
    val javaScriptEnabled = rememberUpdatedState(environment.settings.javaScriptEnabled)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(backgroundColor.toArgb())
                SafeWebViewConfig.apply(this, environment.settings)
                if (javaScriptEnabled.value) {
                    runCatching { addJavascriptInterface(jsBridge, SNIFFER_BRIDGE_NAME) }
                }
                webChromeClient = PopupBlockingWebChromeClient(environment.diagnosticsLogger)
                webViewClient =
                    environment.webViewIntegration.webViewClient(
                        onNavigationRequested = { _, url ->
                            jsBridge.clearPage()
                            tabs.state.value.activeTabId?.let { pageTabAssociator.recordRequest(it, url) }
                        },
                        onPageStart = { webView, url ->
                            jsBridge.clearPage()
                            val page = pageTabAssociator.onPageStartedWithContext(url, tabs.state.value.activeTabId)
                            page.tabId?.let { id ->
                                handlers.actions.onPageStarted(id, url, page.startsNewNavigation)
                                if (id == tabs.state.value.activeTabId) onLoadingChange(true)
                                if (environment.settings.javaScriptEnabled) {
                                    val token = jsBridge.bindPage(url, id)
                                    if (token.isNotEmpty()) {
                                        runCatching { webView.evaluateJavascript(FetchXhrCaptureScript.build(token), null) }
                                    }
                                }
                            }
                        },
                        onPageFinished = { webView ->
                            webView.url?.let { url ->
                                val owner = pageTabAssociator.onPageFinished(url)
                                if (owner == tabs.state.value.activeTabId) onLoadingChange(false)
                                owner?.let { handlers.actions.onPageFinished(it, url, webView.title ?: "") }
                            }
                        },
                        onRequestObserved = { url, headers, pageUrl ->
                            val tabId = pageTabAssociator.tabIdForPage(pageUrl)
                            environment.sniffer.onRequestObserved(
                                url,
                                headers,
                                null,
                                StreamSource.NETWORK,
                                pageUrl,
                                tabId,
                            )
                            environment.subtitleSniffer.onRequestObserved(url, headers, pageUrl, tabId)
                        },
                    )
            }
        },
        update = { webView ->
            SafeWebViewConfig.apply(webView, environment.settings)
            if (environment.settings.javaScriptEnabled) {
                webView.addJavascriptInterface(jsBridge, SNIFFER_BRIDGE_NAME)
                val pageUrl = webView.url
                val owner = pageTabAssociator.tabIdForPage(pageUrl)
                if (pageUrl != null && owner != null && !jsBridge.isBoundTo(pageUrl, owner)) {
                    val token = jsBridge.bindPage(pageUrl, owner)
                    if (token.isNotEmpty()) webView.evaluateJavascript(FetchXhrCaptureScript.build(token), null)
                } else if (owner == null) {
                    jsBridge.clearPage()
                }
            } else {
                runCatching { webView.removeJavascriptInterface(SNIFFER_BRIDGE_NAME) }
                jsBridge.clearPage()
            }
            activeTab?.let { tab ->
                if (pageTabAssociator.shouldLoad(tab.id, tab.url, webView.url)) {
                    jsBridge.clearPage()
                    pageTabAssociator.recordRequest(tab.id, tab.url)
                    onLoadingChange(true)
                    webView.loadUrl(tab.url)
                }
            }
        },
    )
}
