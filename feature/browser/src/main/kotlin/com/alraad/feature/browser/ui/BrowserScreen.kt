package com.alraad.feature.browser.ui

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
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.feature.browser.sniffer.StreamSniffer
import com.alraad.feature.browser.tabs.TabManager

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

    val pageTabAssociator = remember { PageTabAssociator() }
    val liveTabIds = tabState.tabs.map { it.id }.toSet()

    LaunchedEffect(liveTabIds) {
        pageTabAssociator.retainTabs(liveTabIds)
    }

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
        DetectedStreamPicker(
            items = handlers.detectedStreams.streams,
            onPlay = handlers.detectedStreams.onPlay,
            onCopy = handlers.detectedStreams.onCopy,
            onSave = handlers.detectedStreams.onSave,
        )
        BrowserWebView(
            activeTab = activeTab,
            environment =
                BrowserWebViewEnvironment(
                    tabManager = tabManager,
                    webViewIntegration = webViewIntegration,
                    diagnosticsLogger = diagnosticsLogger,
                    sniffer = sniffer,
                    settings = settings,
                ),
            backgroundColor = backgroundColor,
            handlers = handlers,
            pageTabAssociator = pageTabAssociator,
            onLoadingChange = { isLoading = it },
        )
    }
}
