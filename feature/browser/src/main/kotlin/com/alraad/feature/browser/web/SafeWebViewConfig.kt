package com.alraad.feature.browser.web

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import com.alraad.core.common.contracts.AppSettings

/** Applies hardened WebView settings driven by AppSettings (frozen contract). */
object SafeWebViewConfig {
    @SuppressLint("SetJavaScriptEnabled") // user-controlled via AppSettings
    fun apply(
        webView: WebView,
        settings: AppSettings,
    ) {
        with(webView.settings) {
            javaScriptEnabled = settings.javaScriptEnabled
            mixedContentMode =
                if (settings.mixedContentAllowed) {
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = true
            databaseEnabled = false
            // Multiple windows stay technically supported so popup requests route
            // through WebChromeClient.onCreateWindow, which rejects them (see
            // PopupBlockingWebChromeClient). With support disabled, window.open()
            // would instead replace the current page as a top-level navigation.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
    }
}
