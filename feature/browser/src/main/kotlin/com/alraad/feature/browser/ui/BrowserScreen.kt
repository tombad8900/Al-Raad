package com.alraad.feature.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.feature.browser.sniffer.StreamSniffer
import com.alraad.feature.browser.sniffer.SubtitleSniffer
import com.alraad.feature.browser.tabs.BrowserTab
import com.alraad.feature.browser.tabs.TabManager

@Suppress("LongParameterList")
@Composable
fun BrowserScreen(
    tabManager: TabManager,
    webViewIntegration: WebViewIntegrationHelper,
    diagnosticsLogger: DiagnosticsLogger,
    sniffer: StreamSniffer,
    subtitleSniffer: SubtitleSniffer,
    settings: AppSettings,
    modifier: Modifier = Modifier,
    handlers: BrowserHandlers = BrowserHandlers(),
    onExit: () -> Unit = {},
) {
    val tabState by tabManager.state.collectAsState()
    val activeTab = tabState.tabs.firstOrNull { it.id == tabState.activeTabId }
    var isLoading by remember { mutableStateOf(false) }
    val backgroundColor = MaterialTheme.colorScheme.background
    val webViewPool = remember { BrowserWebViewPool() }
    val pageTabAssociators = remember { mutableMapOf<String, PageTabAssociator>() }
    val pageTabAssociator =
        activeTab?.let { pageTabAssociators.getOrPut(it.id) { PageTabAssociator() } } ?: PageTabAssociator()
    val liveTabIds = tabState.tabs.map { it.id }.toSet()

    DisposableEffect(webViewPool) {
        onDispose { webViewPool.clear() }
    }

    LaunchedEffect(liveTabIds) {
        pageTabAssociators.keys.retainAll(liveTabIds)
        webViewPool.retainTabs(liveTabIds)
    }

    LaunchedEffect(Unit) {
        if (tabState.tabs.isEmpty()) tabManager.openTab(settings.homepageUrl)
    }

    BrowserBackNavigation(
        activeTab = activeTab,
        tabManager = tabManager,
        webViewPool = webViewPool,
        onLoadingChange = { isLoading = it },
    )

    Column(modifier = modifier.fillMaxSize()) {
        AddressBar(
            currentUrl = activeTab?.url ?: "",
            isLoading = isLoading,
            onNavigate = { url ->
                val id = tabState.activeTabId ?: tabManager.openTab(url).id
                tabManager.updateTab(id, url = url)
            },
            onBookmarkClick = handlers.actions.onBookmark,
            onExitClick = onExit,
        )
        DetectedStreamPicker(
            items = handlers.detectedStreams.streams,
            onPlay = handlers.detectedStreams.onPlay,
            onCopy = handlers.detectedStreams.onCopy,
            onSave = handlers.detectedStreams.onSave,
        )
        key(activeTab?.id) {
            BrowserWebView(
                activeTab = activeTab,
                environment =
                    BrowserWebViewEnvironment(
                        tabManager = tabManager,
                        webViewIntegration = webViewIntegration,
                        diagnosticsLogger = diagnosticsLogger,
                        sniffer = sniffer,
                        subtitleSniffer = subtitleSniffer,
                        settings = settings,
                        webViewPool = webViewPool,
                    ),
                backgroundColor = backgroundColor,
                handlers = handlers,
                pageTabAssociator = pageTabAssociator,
                onLoadingChange = { isLoading = it },
            )
        }
    }
}

@Composable
private fun BrowserBackNavigation(
    activeTab: BrowserTab?,
    tabManager: TabManager,
    webViewPool: BrowserWebViewPool,
    onLoadingChange: (Boolean) -> Unit,
) {
    BackHandler {
        val tab = activeTab ?: return@BackHandler
        val webView = webViewPool.get(tab.id)
        handleBrowserBack(
            tabUrl = tab.url,
            canGoBack = { webView?.canGoBack() == true },
            goBack = { webView?.goBack() },
            returnToSearch = {
                webView?.apply {
                    loadUrl("about:blank")
                    clearHistory()
                }
                tabManager.updateTab(tab.id, url = "")
                onLoadingChange(false)
            },
        )
    }
}
