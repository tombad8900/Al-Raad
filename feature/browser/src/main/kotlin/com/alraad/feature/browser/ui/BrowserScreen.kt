package com.alraad.feature.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * Browser shell: the WebView's page-start/page-finish callbacks are associated with
 * the tab that initiated each navigation, not whichever tab happens to be active
 * when the asynchronous finish callback arrives.
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
    var saveDialogStream by remember { mutableStateOf<com.alraad.core.common.contracts.StreamUrl?>(null) }
    val backgroundColor = MaterialTheme.colorScheme.background

    // Load requests are recorded before loadUrl; onPageStarted binds the actual URL
    // (including redirects) to the originating tab, so finish callbacks landing
    // after a tab switch still update the right tab.
    val pageTabAssociator = remember { PageTabAssociator() }

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
        handlers.detectedStreams.streams.firstOrNull()?.let { stream ->
            DetectedStreamBar(
                stream = stream,
                streams = handlers.detectedStreams,
                onSaveClick = { saveDialogStream = stream },
            )
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
        saveDialogStream?.let { stream ->
            SaveStreamDialog(
                initialTitle = android.net.Uri.parse(stream.url).host ?: stream.url,
                onConfirm = { title ->
                    handlers.detectedStreams.onSave(stream, title)
                    saveDialogStream = null
                },
                onDismiss = { saveDialogStream = null },
            )
        }
    }
}

/** Stream bar: play / copy / save actions for the most recently detected stream. */
@Composable
private fun DetectedStreamBar(
    stream: com.alraad.core.common.contracts.StreamUrl,
    streams: DetectedStreamsUi,
    onSaveClick: () -> Unit,
) {
    val host = android.net.Uri.parse(stream.url).host ?: stream.url
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.TextButton(onClick = { streams.onPlay(stream) }) {
            androidx.compose.material3.Text(text = "▶ $host")
        }
        androidx.compose.material3.TextButton(onClick = { streams.onCopy(stream) }) {
            androidx.compose.material3.Text(text = "نسخ")
        }
        androidx.compose.material3.TextButton(onClick = onSaveClick) {
            androidx.compose.material3.Text(text = "حفظ")
        }
    }
}
