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
        handlers.detectedStreams.streams.lastOrNull()?.let { item ->
            DetectedStreamBar(item = item, streams = handlers.detectedStreams)
        }
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

/** Stream bar: play / copy / save for the most recently detected stream (picker UI lands in the follow-up PR). */
@Composable
private fun DetectedStreamBar(
    item: DetectedStreamItem,
    streams: DetectedStreamsUi,
) {
    val host = android.net.Uri.parse(item.stream.url).host ?: item.stream.url
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.TextButton(onClick = { streams.onPlay(item) }) {
            androidx.compose.material3.Text(text = "▶ $host")
        }
        androidx.compose.material3.TextButton(onClick = { streams.onCopy(item) }) {
            androidx.compose.material3.Text(text = "نسخ")
        }
        androidx.compose.material3.TextButton(onClick = { streams.onSave(item, host) }) {
            androidx.compose.material3.Text(text = "حفظ")
        }
    }
}
