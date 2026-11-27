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

    fun shouldLoad(tabId: String, tabUrl: String, webViewUrl: String?): Boolean =
        tabUrl.isNotBlank() && tabUrl != webViewUrl && (tabId to tabUrl) != lastRequestedNavigation

    fun recordRequest(tabId: String, tabUrl: String) {
        lastRequestedNavigation = tabId to tabUrl
        pageTabIds.entries.removeAll { it.value == tabId }
        pendingNavigationTabs.add(tabUrl to tabId)
        if (pendingNavigationTabs.size > MAX_PENDING_NAVIGATIONS) pendingNavigationTabs.removeAt(0)
    }

    fun onPageStartedWithContext(url: String, activeTabId: String?): PageStartAssociation {
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

    fun onPageFinished(url: String): String? = pageTabIds[url]

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
