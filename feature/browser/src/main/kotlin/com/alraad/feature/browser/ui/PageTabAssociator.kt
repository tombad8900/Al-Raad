package com.alraad.feature.browser.ui

/**
 * Associates WebView page loads with the tab that initiated them. A load request is
 * recorded before loadUrl; onPageStarted binds the actual URL (including redirects)
 * to the originating tab, so a finish callback landing after a tab switch still
 * updates the right tab instead of whichever tab is active then.
 */
class PageTabAssociator {
    private val pendingNavigationTabs = mutableListOf<Pair<String, String>>()
    private val pageTabIds = mutableMapOf<String, String>()
    private var lastRequestedNavigation: Pair<String, String>? = null

    /** Returns true when this tab's URL must be (re)loaded into the WebView. */
    fun shouldLoad(
        tabId: String,
        tabUrl: String,
        webViewUrl: String?,
    ): Boolean = tabUrl.isNotBlank() && tabUrl != webViewUrl && (tabId to tabUrl) != lastRequestedNavigation

    /** Records an outgoing loadUrl request for [tabId]. */
    fun recordRequest(
        tabId: String,
        tabUrl: String,
    ) {
        lastRequestedNavigation = tabId to tabUrl
        pendingNavigationTabs.add(tabUrl to tabId)
    }

    /** Binds a started page URL to its originating tab, falling back to [activeTabId]. */
    fun onPageStarted(
        url: String,
        activeTabId: String?,
    ): String? {
        val matchIndex = pendingNavigationTabs.indexOfFirst { it.first == url }
        val ownerTabId =
            when {
                matchIndex >= 0 -> pendingNavigationTabs.removeAt(matchIndex).second
                pendingNavigationTabs.isNotEmpty() -> pendingNavigationTabs.removeAt(0).second
                else -> activeTabId
            }
        if (ownerTabId != null) pageTabIds[url] = ownerTabId
        return ownerTabId
    }

    /** Resolves and forgets the owning tab of a finished page URL. */
    fun onPageFinished(url: String): String? = pageTabIds.remove(url)
}
