package com.alraad.feature.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
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
 * Browser shell: RTL AddressBar on top of a hardened WebView. Navigation from the
 * address bar updates the active tab in TabManager; the WebView loads the active
 * tab's URL. Ad blocking and sniffing come from WebViewIntegrationHelper.
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
    val loadingByTab = remember { mutableStateMapOf<String, Boolean>() }
    val isLoading = loadingByTab[activeTab?.id] ?: false
    val backgroundColor = MaterialTheme.colorScheme.background
    val webViews = remember { mutableMapOf<String, WebView>() }

    LaunchedEffect(tabState.tabs.map { it.id }) {
        val liveTabIds = tabState.tabs.mapTo(mutableSetOf()) { it.id }
        webViews.keys.filterNot { it in liveTabIds }.forEach { id ->
            loadingByTab.remove(id)
            webViews.remove(id)?.apply {
                stopLoading()
                destroy()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webViews.values.forEach { webView ->
                webView.stopLoading()
                webView.destroy()
            }
            webViews.clear()
        }
    }

    // Open the homepage tab on first launch so the WebView is never left blank.
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
        // Show only links associated with the active tab's current page.
        handlers.detectedStreams.streams.takeIf { it.isNotEmpty() }?.let { items ->
            DetectedStreamBar(items = items, actions = handlers.detectedStreams)
        }
        activeTab?.let { tab ->
            key(tab.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        webViews.getOrPut(tab.id) {
                            WebView(context).apply {
                                setBackgroundColor(backgroundColor.toArgb())
                                SafeWebViewConfig.apply(this, settings)
                                webChromeClient = PopupBlockingWebChromeClient(diagnosticsLogger)
                                webViewClient =
                                    webViewIntegration.webViewClient(
                                        onPageFinished = { webView ->
                                            loadingByTab[tab.id] = false
                                            webView.url?.let { loaded ->
                                                handlers.actions.onPageVisited(loaded, webView.title ?: "", tab.id)
                                            }
                                        },
                                        onRequestObserved = { url, headers, pageUrl ->
                                            sniffer.onRequestObserved(
                                                url,
                                                headers,
                                                null,
                                                StreamSource.NETWORK,
                                                pageUrl,
                                                tab.id,
                                            )
                                        },
                                        onPageStart = { _, _ -> loadingByTab[tab.id] = true },
                                    )
                            }
                        }
                    },
                    update = { webView ->
                        tab.url.takeIf { it.isNotBlank() && it != webView.url }?.let {
                            loadingByTab[tab.id] = true
                            webView.loadUrl(it)
                        }
                    },
                )
            }
        }
    }
}

/** Current-page stream picker with a direct path when only one URL was detected. */
@Composable
private fun DetectedStreamBar(
    items: List<DetectedStreamItem>,
    actions: DetectedStreamsUi,
) {
    val sortedItems = remember(items) { sortDetectedStreamItems(items) }
    var selectedUrl by remember(sortedItems) { mutableStateOf(sortedItems.firstOrNull()?.stream?.url) }
    val selected = sortedItems.firstOrNull { it.stream.url == selectedUrl } ?: sortedItems.firstOrNull()
    var selectorExpanded by remember { mutableStateOf(false) }
    var saveDialogOpen by remember { mutableStateOf(false) }
    var saveTitle by remember(selected?.stream?.url) {
        mutableStateOf(selected?.stream?.let { android.net.Uri.parse(it.url).host ?: it.url }.orEmpty())
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("روابط البث المكتشفة: ${sortedItems.size}", style = MaterialTheme.typography.labelLarge)
        if (sortedItems.size > 1) {
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { selectorExpanded = true }) {
                    Text("اختيار رابط البث")
                }
                DropdownMenu(expanded = selectorExpanded, onDismissRequest = { selectorExpanded = false }) {
                    sortedItems.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.stream.format.name)
                                    Text(item.stream.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            },
                            onClick = {
                                selectedUrl = item.stream.url
                                selectorExpanded = false
                            },
                        )
                    }
                }
            }
        }
        selected?.let { item ->
            Text(
                item.stream.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { actions.onPlay(item) }) {
                    Text("تشغيل")
                }
                TextButton(onClick = { actions.onCopy(item) }) {
                    Text("نسخ الرابط")
                }
                TextButton(
                    onClick = {
                        saveTitle = android.net.Uri.parse(item.stream.url).host ?: item.stream.url
                        saveDialogOpen = true
                    },
                ) {
                    Text("حفظ في المكتبة")
                }
            }
        }
    }

    if (saveDialogOpen && selected != null) {
        AlertDialog(
            onDismissRequest = { saveDialogOpen = false },
            title = { Text("حفظ البث") },
            text = {
                OutlinedTextField(
                    value = saveTitle,
                    onValueChange = { saveTitle = it },
                    label = { Text("اسم البث") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = saveTitle.isNotBlank(),
                    onClick = {
                        actions.onSave(selected, saveTitle.trim())
                        saveDialogOpen = false
                    },
                ) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogOpen = false }) { Text("إلغاء") }
            },
        )
    }
}

internal fun sortDetectedStreamItems(items: List<DetectedStreamItem>): List<DetectedStreamItem> =
    items.distinctBy { it.stream.url }.sortedWith(compareBy({ it.stream.format.name }, { it.stream.url }))
