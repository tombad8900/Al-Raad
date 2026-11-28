package com.alraad.feature.browser.ui

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient

/**
 * Keeps one WebView per live tab for the lifetime of the singleton TabManager.
 * The application context prevents a retained tab from retaining a navigation Activity.
 */
class BrowserWebViewPool(context: Context) {
    private val applicationContext = context.applicationContext
    private val views = mutableMapOf<String, WebView>()

    internal var activeTabId: String? = null
        private set

    internal var timersPaused: Boolean = false
        private set

    fun obtain(tabId: String): WebView =
        views.getOrPut(tabId) { WebView(applicationContext) }.also { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            if (activeTabId == tabId) webView.onResume() else pause(webView)
        }

    fun get(tabId: String): WebView? = views[tabId]

    /** Resume only the visible tab; background tabs are paused independently. */
    fun activate(tabId: String?) {
        if (timersPaused) {
            views.values.firstOrNull()?.resumeTimers()
            timersPaused = false
        }
        activeTabId = tabId
        views.forEach { (id, webView) ->
            if (id == tabId) {
                webView.onResume()
            } else {
                pause(webView)
            }
        }
    }

    /** Called when BrowserScreen is not visible or the host Activity is pausing. */
    fun pauseAll() {
        activeTabId = null
        views.values.forEach(::pause)
        if (!timersPaused) {
            views.values.firstOrNull()?.pauseTimers()
            timersPaused = views.isNotEmpty()
        }
    }

    fun retainTabs(tabIds: Set<String>) {
        val removed = views.keys.filterNot { it in tabIds }
        removed.forEach(::release)
        if (activeTabId !in tabIds) activeTabId = null
    }

    fun release(tabId: String) {
        val removed = views.remove(tabId)
        if (timersPaused && views.isEmpty() && removed != null) {
            removed.resumeTimers()
            timersPaused = false
        }
        removed?.let(::destroy)
        if (activeTabId == tabId) activeTabId = null
    }

    /** Reserved for the actual owner (TabManager) when its lifetime is reset. */
    fun clear() {
        if (timersPaused) {
            views.values.firstOrNull()?.resumeTimers()
            timersPaused = false
        }
        views.keys.toList().forEach(::release)
        activeTabId = null
    }

    private fun pause(webView: WebView) {
        // onPause is a focus/audio hint; it does NOT guarantee that page JavaScript or
        // timers stop. Pause media elements explicitly as a best-effort per-WebView action.
        webView.evaluateJavascript(
            "(function(){document.querySelectorAll('video,audio').forEach(function(m){m.pause();});})();",
            null,
        )
        webView.onPause()
    }

    private fun destroy(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.removeAllViews()
        webView.destroy()
    }
}
