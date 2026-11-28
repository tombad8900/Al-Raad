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
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(backgroundColor.toArgb())
                SafeWebViewConfig.apply(this, environment.settings)
                webChromeClient = PopupBlockingWebChromeClient(environment.diagnosticsLogger)
                webViewClient =
                    environment.webViewIntegration.webViewClient(
                        onNavigationRequested = { _, url ->
                            tabs.state.value.activeTabId?.let { pageTabAssociator.recordRequest(it, url) }
                        },
                        onPageDocumentStarted = { _, url ->
                            val page = pageTabAssociator.onPageStartedWithContext(url, tabs.state.value.activeTabId)
                            page.tabId?.let { id ->
                                handlers.actions.onPageStarted(id, url, page.startsNewNavigation, page.documentId)
                                if (id == tabs.state.value.activeTabId) onLoadingChange(true)
                            }
                            page.documentId
                        },
                        onPageUrlChanged = { _, previousUrl, url, _ ->
                            pageTabAssociator.onPageUrlChanged(previousUrl, url)?.also { association ->
                                handlers.actions.onPageUrlChanged(
                                    association.tabId,
                                    previousUrl,
                                    url,
                                    association.documentId,
                                )
                            }?.documentId
                        },
                        onPageFinished = { webView ->
                            webView.url?.let { url ->
                                val owner = pageTabAssociator.onPageFinished(url)
                                if (owner == tabs.state.value.activeTabId) onLoadingChange(false)
                                owner?.let { handlers.actions.onPageFinished(it, url, webView.title ?: "") }
                            }
                        },
                        onRequestObservedWithContext = { url, headers, pageUrl, documentId ->
                            val association =
                                if (documentId != null) {
                                    pageTabAssociator.associationForDocument(documentId)
                                } else {
                                    pageTabAssociator.associationForPage(pageUrl)
                                }
                            val tabId = association?.tabId
                            val sourceDocumentId = association?.documentId
                            environment.sniffer.onRequestObserved(
                                url,
                                headers,
                                null,
                                StreamSource.NETWORK,
                                pageUrl,
                                tabId,
                                sourceDocumentId,
                            )
                            environment.subtitleSniffer.onRequestObserved(url, headers, pageUrl, tabId)
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
