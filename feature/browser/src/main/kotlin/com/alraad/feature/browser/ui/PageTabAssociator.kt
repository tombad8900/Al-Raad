package com.alraad.feature.browser.ui

class PageTabAssociator {
    private val pendingNavigationTabs = mutableListOf<Pair<String, String>>()
    private val pageTabIds =
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_PAGE_MAPPINGS
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
        val matchIndex = pendingNavigationTabs.indexOfFirst { it.first == url }
        val displacedRedirect = matchIndex < 0 && pendingNavigationTabs.isNotEmpty()
        val ownerTabId =
            when {
                matchIndex >= 0 -> pendingNavigationTabs.removeAt(matchIndex).second
                displacedRedirect -> lastBoundTabId
                lastBoundTabId != null && lastBoundTabId != activeTabId -> lastBoundTabId
                else -> activeTabId
            }
        val startsNewNavigation = matchIndex >= 0 || (lastBoundTabId == null && ownerTabId == activeTabId)
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
