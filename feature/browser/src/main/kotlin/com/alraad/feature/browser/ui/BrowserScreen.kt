package com.alraad.feature.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    var saveTitle by remember { mutableStateOf("") }
    val backgroundColor = MaterialTheme.colorScheme.background

    // A load request is queued before loadUrl; onPageStarted binds its actual URL
    // (including redirects) to the originating tab. Finished callbacks then use
    // that immutable association even after the user switches tabs.
    val pendingNavigationTabs = remember { mutableStateOf(mutableListOf<Pair<String, String>>()) }.value
    val pageTabIds = remember { mutableStateOf(mutableMapOf<String, String>()) }.value
    var lastRequestedNavigation by remember { mutableStateOf<Pair<String, String>?>(null) }

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
                onSaveClick = {
                    saveTitle = android.net.Uri.parse(stream.url).host ?: stream.url
                    saveDialogStream = stream
                },
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(backgroundColor.toArgb())
                    SafeWebViewConfig.apply(this, settings)
                    webChromeClient = PopupBlockingWebChromeClient(diagnosticsLogger)
                    webViewClient =
                        webViewIntegration.webViewClient(
                            onPageStarted = { _, url ->
                                val matchIndex = pendingNavigationTabs.indexOfFirst { it.first == url }
                                val ownerTabId =
                                    if (matchIndex >= 0) {
                                        pendingNavigationTabs.removeAt(matchIndex).second
                                    } else if (pendingNavigationTabs.isNotEmpty()) {
                                        pendingNavigationTabs.removeAt(0).second
                                    } else {
                                        tabManager.state.value.activeTabId
                                    }
                                if (ownerTabId != null) {
                                    pageTabIds[url] = ownerTabId
                                    if (ownerTabId == tabManager.state.value.activeTabId) isLoading = true
                                }
                            },
                            onPageFinishedWithUrl = { webView, loadedUrl ->
                                val ownerTabId = pageTabIds.remove(loadedUrl) ?: tabManager.state.value.activeTabId
                                if (ownerTabId == tabManager.state.value.activeTabId) isLoading = false
                                if (ownerTabId != null) {
                                    handlers.actions.onPageFinished(ownerTabId, loadedUrl, webView.title ?: "")
                                }
                            },
                            onRequestObserved = { url, headers, pageUrl ->
                                sniffer.onRequestObserved(url, headers, null, StreamSource.NETWORK, pageUrl)
                            },
                        )
                }
            },
            update = { webView ->
                activeTab?.let { tab ->
                    val request = tab.id to tab.url
                    if (tab.url.isNotBlank() && tab.url != webView.url && request != lastRequestedNavigation) {
                        lastRequestedNavigation = request
                        pendingNavigationTabs.add(tab.url to tab.id)
                        isLoading = true
                        webView.loadUrl(tab.url)
                    }
                }
            },
        )
        saveDialogStream?.let { stream ->
            AlertDialog(
                onDismissRequest = { saveDialogStream = null },
                title = { Text("حفظ البث") },
                text = {
                    OutlinedTextField(
                        value = saveTitle,
                        onValueChange = { saveTitle = it },
                        singleLine = true,
                        label = { Text("اسم البث") },
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            handlers.detectedStreams.onSave(stream, saveTitle.trim())
                            saveDialogStream = null
                        },
                    ) {
                        Text("حفظ")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { saveDialogStream = null }) {
                        Text("إلغاء")
                    }
                },
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
