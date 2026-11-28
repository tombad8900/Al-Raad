package com.alraad.feature.browser.ui

import android.view.ViewGroup
import android.webkit.WebView

class BrowserWebViewPool {
    private val views = mutableMapOf<String, WebView>()

    fun obtain(tabId: String, factory: () -> WebView): WebView {
        val webView = views.getOrPut(tabId, factory)
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }

    fun get(tabId: String): WebView? = views[tabId]

    fun retainTabs(tabIds: Set<String>) {
        val removed = views.keys.filterNot { it in tabIds }
        removed.forEach { id -> views.remove(id)?.let(::destroy) }
    }

    fun clear() {
        views.values.toList().forEach(::destroy)
        views.clear()
    }

    private fun destroy(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
    }
}
