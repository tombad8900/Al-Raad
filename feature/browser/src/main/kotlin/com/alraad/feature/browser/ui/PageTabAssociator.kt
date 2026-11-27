package com.alraad.feature.browser.ui

/**
 * Associates WebView page loads with the tab that initiated them. A load request is
 * recorded before loadUrl; onPageStarted binds the actual URL (including redirects)
 * to the originating tab, so asynchronous callbacks do not use the currently active tab.
 */
class PageTabAssociator {
    private val pendingNavigationTabs = mutableListOf<Pair<String, String>>()
    private val pageTabIds =
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_PAGE_MAPPINGS
        }
    private var lastRequestedNavigation: Pair<String, String>? = null
    private var lastBoundTabId: String? = null

    /** Returns true when this tab's URL must be (re)loaded into the WebView. */
    fun shouldLoad(
        tabId: String,
        tabUrl: String,
        webViewUrl: String?,
    ): Boolean = tabUrl.isNotBlank() && tabUrl != webViewUrl && (tabId to tabUrl) != lastRequestedNavigation

    /** Records an outgoing loadUrl request for [tabId] and retires its previous page mappings. */
    fun recordRequest(
        tabId: String,
        tabUrl: String,
    ) {
        lastRequestedNavigation = tabId to tabUrl
        pageTabIds.entries.removeAll { it.value == tabId }
        pendingNavigationTabs.add(tabUrl to tabId)
        if (pendingNavigationTabs.size > MAX_PENDING_NAVIGATIONS) {
            pendingNavigationTabs.removeAt(0)
        }
    }

    /** Binds a started page URL and reports whether it begins a new page for its tab. */
    fun onPageStartedWithContext(
        url: String,
        activeTabId: String?,
    ): PageStartAssociation {
        val matchIndex = pendingNavigationTabs.indexOfFirst { it.first == url }
        val displacedRedirect = matchIndex < 0 && pendingNavigationTabs.isNotEmpty()
        val ownerTabId =
            when {
                matchIndex >= 0 -> pendingNavigationTabs.removeAt(matchIndex).second
                displacedRedirect -> lastBoundTabId
                lastBoundTabId != null && lastBoundTabId != activeTabId -> lastBoundTabId
                else -> activeTabId
            }
        if (ownerTabId != null) {
            if (!displacedRedirect && ownerTabId == activeTabId) {
                pageTabIds.entries.removeAll { it.value == ownerTabId }
            }
            pageTabIds[url] = ownerTabId
            lastBoundTabId = ownerTabId
        }
        return PageStartAssociation(
            tabId = ownerTabId,
            startsNewNavigation = matchIndex >= 0 || (lastBoundTabId == null && ownerTabId == activeTabId),
        )
    }

    /** Backward-compatible tab-only result used by existing associator callers/tests. */
    fun onPageStarted(
        url: String,
        activeTabId: String?,
    ): String? = onPageStartedWithContext(url, activeTabId).tabId

    /** Page mappings survive finish so late subresource requests retain their tab identity. */
    fun onPageFinished(url: String): String? = pageTabIds[url]

    /** Resolves the tab for network requests observed after the page-finished callback. */
    fun tabIdForPage(url: String?): String? = url?.let { pageTabIds[it] }

    private companion object {
        const val MAX_PAGE_MAPPINGS = 256
        const val MAX_PENDING_NAVIGATIONS = 64
    }
}

data class PageStartAssociation(
    val tabId: String?,
    val startsNewNavigation: Boolean,
)
