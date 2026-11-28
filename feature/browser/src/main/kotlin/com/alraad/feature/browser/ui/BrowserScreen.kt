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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val applicationContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val webViewPool = remember(tabManager, applicationContext) { tabManager.webViewPool(applicationContext) }
    val pageTabAssociators = remember { mutableMapOf<String, PageTabAssociator>() }
    val pageTabAssociator =
        activeTab?.let { pageTabAssociators.getOrPut(it.id) { PageTabAssociator() } } ?: PageTabAssociator()
    val liveTabIds = tabState.tabs.map { it.id }.toSet()
    val browserWebViewEnvironment =
        createBrowserWebViewEnvironment(
            tabManager,
            webViewIntegration,
            diagnosticsLogger,
            sniffer,
            subtitleSniffer,
            settings,
            webViewPool,
        )

    BrowserTabLifecycle(
        tabManager = tabManager,
        settings = settings,
        liveTabIds = liveTabIds,
        activeTabId = activeTab?.id,
        pageTabAssociators = pageTabAssociators,
        webViewPool = webViewPool,
        lifecycleOwner = lifecycleOwner,
    )

    BrowserBackNavigation(
        activeTab = activeTab,
        tabManager = tabManager,
        webViewPool = webViewPool,
        onLoadingChange = { isLoading = it },
    )

    Column(modifier = modifier.fillMaxSize()) {
        BrowserToolbar(
            activeTab = activeTab,
            isLoading = isLoading,
            handlers = handlers,
            onNavigate = { url ->
                val id = tabState.activeTabId ?: tabManager.openTab(url).id
                tabManager.updateTab(id, url = url)
            },
            onExit = onExit,
        )
        BrowserWebViewHost(
            activeTab = activeTab,
            environment = browserWebViewEnvironment,
            backgroundColor = backgroundColor,
            handlers = handlers,
            pageTabAssociator = pageTabAssociator,
            onLoadingChange = { isLoading = it },
        )
    }
}

@Composable
private fun BrowserTabLifecycle(
    tabManager: TabManager,
    settings: AppSettings,
    liveTabIds: Set<String>,
    activeTabId: String?,
    pageTabAssociators: MutableMap<String, PageTabAssociator>,
    webViewPool: BrowserWebViewPool,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    LaunchedEffect(liveTabIds) {
        pageTabAssociators.keys.retainAll(liveTabIds)
        webViewPool.retainTabs(liveTabIds)
    }
    LaunchedEffect(activeTabId) {
        webViewPool.activate(activeTabId)
    }
    DisposableEffect(lifecycleOwner, webViewPool, tabManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webViewPool.pauseAll()
                Lifecycle.Event.ON_RESUME -> webViewPool.activate(tabManager.state.value.activeTabId)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Preserve WebViews and their navigation history; only pause them while this
            // destination is absent. TabManager owns final release at tab/process lifetime.
            webViewPool.pauseAll()
        }
    }
    LaunchedEffect(Unit) {
        if (tabManager.state.value.tabs.isEmpty()) tabManager.openTab(settings.homepageUrl)
    }
}

private fun createBrowserWebViewEnvironment(
    tabManager: TabManager,
    webViewIntegration: WebViewIntegrationHelper,
    diagnosticsLogger: DiagnosticsLogger,
    sniffer: StreamSniffer,
    subtitleSniffer: SubtitleSniffer,
    settings: AppSettings,
    webViewPool: BrowserWebViewPool,
): BrowserWebViewEnvironment =
    BrowserWebViewEnvironment(
        tabManager = tabManager,
        webViewIntegration = webViewIntegration,
        diagnosticsLogger = diagnosticsLogger,
        sniffer = sniffer,
        subtitleSniffer = subtitleSniffer,
        settings = settings,
        webViewPool = webViewPool,
    )

@Composable
private fun BrowserWebViewHost(
    activeTab: BrowserTab?,
    environment: BrowserWebViewEnvironment,
    backgroundColor: Color,
    handlers: BrowserHandlers,
    pageTabAssociator: PageTabAssociator,
    onLoadingChange: (Boolean) -> Unit,
) {
    key(activeTab?.id) {
        BrowserWebView(
            activeTab = activeTab,
            environment = environment,
            backgroundColor = backgroundColor,
            handlers = handlers,
            pageTabAssociator = pageTabAssociator,
            onLoadingChange = onLoadingChange,
        )
    }
}

@Composable
private fun BrowserToolbar(
    activeTab: BrowserTab?,
    isLoading: Boolean,
    handlers: BrowserHandlers,
    onNavigate: (String) -> Unit,
    onExit: () -> Unit,
) {
    AddressBar(
        currentUrl = activeTab?.url ?: "",
        isLoading = isLoading,
        onNavigate = onNavigate,
        onBookmarkClick = handlers.actions.onBookmark,
        onExitClick = onExit,
    )
    DetectedStreamPicker(
        items = handlers.detectedStreams.streams,
        onPlay = handlers.detectedStreams.onPlay,
        onCopy = handlers.detectedStreams.onCopy,
        onSave = handlers.detectedStreams.onSave,
    )
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
