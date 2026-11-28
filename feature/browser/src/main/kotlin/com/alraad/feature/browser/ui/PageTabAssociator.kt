package com.alraad.feature.browser.ui

class PageTabAssociator {
    private val pendingNavigationTabs = mutableListOf<Pair<String, String>>()
    private val pageTabIds =
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?,
            ): Boolean = size > MAX_PAGE_MAPPINGS
        }
    private var lastRequestedNavigation: Pair<String, String>? = null
    private var lastBoundTabId: String? = null

    @Synchronized
    fun shouldLoad(
        tabId: String,
        tabUrl: String,
        webViewUrl: String?,
    ): Boolean {
        return tabUrl.isNotBlank() && tabUrl != webViewUrl && (tabId to tabUrl) != lastRequestedNavigation
    }

    @Synchronized
    fun recordRequest(
        tabId: String,
        tabUrl: String,
    ) {
        lastRequestedNavigation = tabId to tabUrl
        pageTabIds.entries.removeAll { it.value == tabId }
        // One WebView has one in-flight top-level navigation per tab. Repeated
        // address-bar / shouldOverride callbacks must not leave stale pending owners.
        pendingNavigationTabs.removeAll { it.second == tabId }
        pendingNavigationTabs.add(tabUrl to tabId)
        if (pendingNavigationTabs.size > MAX_PENDING_NAVIGATIONS) pendingNavigationTabs.removeAt(0)
    }

    /** Remove navigation and page ownership state for tabs that have been closed. */
    @Synchronized
    fun retainTabs(activeTabIds: Set<String>) {
        pendingNavigationTabs.removeAll { it.second !in activeTabIds }
        pageTabIds.entries.removeAll { it.value !in activeTabIds }
        if (lastBoundTabId !in activeTabIds) lastBoundTabId = null
        if (lastRequestedNavigation?.first !in activeTabIds) lastRequestedNavigation = null
    }

    @Synchronized
    fun onPageStartedWithContext(
        url: String,
        activeTabId: String?,
    ): PageStartAssociation {
        // Resource callbacks can race ahead of onPageStarted. Resolve an exact
        // pending navigation without consuming it; the start callback binds it.
        val pendingMatch = pendingNavigationTabs.lastOrNull { it.first == url }
        val displacedRedirect = pendingMatch == null && pendingNavigationTabs.isNotEmpty()
        val ownerTabId =
            when {
                pendingMatch != null -> pendingMatch.second
                displacedRedirect -> lastBoundTabId
                lastBoundTabId != null && lastBoundTabId != activeTabId -> lastBoundTabId
                else -> activeTabId
            }
        val startsNewNavigation = pendingMatch != null || (lastBoundTabId == null && ownerTabId == activeTabId)
        if (pendingMatch != null) {
            pendingNavigationTabs.removeAll { it.first == url && it.second == pendingMatch.second }
        }
        if (ownerTabId != null) {
            if (startsNewNavigation) pageTabIds.entries.removeAll { it.value == ownerTabId }
            pageTabIds[url] = ownerTabId
            lastBoundTabId = ownerTabId
        }
        return PageStartAssociation(ownerTabId, startsNewNavigation)
    }

    @Synchronized
    fun onPageFinished(url: String): String? = pageTabIds[url]

    @Synchronized
    fun tabIdForPage(url: String?): String? =
        url?.let { pageTabIds[it] ?: pendingNavigationTabs.lastOrNull { pending -> pending.first == it }?.second }

    private companion object {
        const val MAX_PAGE_MAPPINGS = 256
        const val MAX_PENDING_NAVIGATIONS = 64
    }
}

data class PageStartAssociation(
    val tabId: String?,
    val startsNewNavigation: Boolean,
)
