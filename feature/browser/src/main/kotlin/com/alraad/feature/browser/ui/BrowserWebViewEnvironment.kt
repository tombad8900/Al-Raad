package com.alraad.feature.browser.ui

import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.feature.browser.sniffer.SubtitleSniffer
import com.alraad.feature.browser.tabs.TabManager

/** Services the WebView surface needs, grouped to keep the composable signature small. */
data class BrowserWebViewEnvironment(
    val tabManager: TabManager,
    val webViewIntegration: WebViewIntegrationHelper,
    val diagnosticsLogger: DiagnosticsLogger,
    val sniffer: com.alraad.feature.browser.sniffer.StreamSniffer,
    val subtitleSniffer: SubtitleSniffer,
    val settings: AppSettings,
    val webViewPool: BrowserWebViewPool,
)
