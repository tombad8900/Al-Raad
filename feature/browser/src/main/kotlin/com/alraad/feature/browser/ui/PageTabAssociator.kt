package com.alraad.feature.browser.ui

import java.util.UUID

data class PageDocumentAssociation(
    val tabId: String,
    val documentId: String,
)

data class PageStartAssociation(
    val tabId: String?,
    val startsNewNavigation: Boolean,
    val documentId: String? = null,
)

/**
 * Associates top-level document URLs with the tab and navigation generation that owns them.
 *
 * Resource URLs (including cross-origin iframe hosts) are deliberately not compared with the
 * top-level hostname. Their owner comes from the WebView document context.
 */
class PageTabAssociator {
    private data class PendingNavigation(
        val url: String,
        val tabId: String,
        val documentId: String,
    )

    private val pendingNavigationTabs = mutableListOf<PendingNavigation>()
    private val pageDocuments =
        object : LinkedHashMap<String, PageDocumentAssociation>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, PageDocumentAssociation>?,
            ): Boolean = size > MAX_PAGE_MAPPINGS
        }
    private val documentOwners = mutableMapOf<String, PageDocumentAssociation>()
    private var lastRequestedNavigation: Pair<String, String>? = null
    private var lastBoundDocument: PageDocumentAssociation? = null

    @Synchronized
    fun shouldLoad(
        tabId: String,
        tabUrl: String,
        webViewUrl: String?,
    ): Boolean = tabUrl.isNotBlank() && tabUrl != webViewUrl && (tabId to tabUrl) != lastRequestedNavigation

    /** Returns a stable token for repeated callbacks belonging to the same pending request. */
    @Synchronized
    fun recordRequest(
        tabId: String,
        tabUrl: String,
    ): String {
        val existing = pendingNavigationTabs.lastOrNull { it.tabId == tabId && it.url == tabUrl }
        if (existing != null) return existing.documentId

        lastRequestedNavigation = tabId to tabUrl
        pageDocuments.entries.removeAll { it.value.tabId == tabId }
        documentOwners.entries.removeAll { it.value.tabId == tabId }
        pendingNavigationTabs.removeAll { it.tabId == tabId }
        val pending = PendingNavigation(tabUrl, tabId, UUID.randomUUID().toString())
        pendingNavigationTabs.add(pending)
        if (pendingNavigationTabs.size > MAX_PENDING_NAVIGATIONS) pendingNavigationTabs.removeAt(0)
        return pending.documentId
    }

    /** Remove navigation and page ownership state for tabs that have been closed. */
    @Synchronized
    fun retainTabs(activeTabIds: Set<String>) {
        pendingNavigationTabs.removeAll { it.tabId !in activeTabIds }
        pageDocuments.entries.removeAll { it.value.tabId !in activeTabIds }
        documentOwners.entries.removeAll { it.value.tabId !in activeTabIds }
        if (lastBoundDocument?.tabId !in activeTabIds) lastBoundDocument = null
        if (lastRequestedNavigation?.first !in activeTabIds) lastRequestedNavigation = null
    }

    @Synchronized
    fun onPageStartedWithContext(
        url: String,
        activeTabId: String?,
    ): PageStartAssociation {
        val pendingMatch = pendingNavigationTabs.lastOrNull { it.url == url }
        val displacedRedirect = pendingMatch == null && pendingNavigationTabs.isNotEmpty()
        val previousDocument = lastBoundDocument
        val ownerTabId =
            when {
                pendingMatch != null -> pendingMatch.tabId
                displacedRedirect -> previousDocument?.tabId
                previousDocument != null && previousDocument.tabId != activeTabId -> previousDocument.tabId
                else -> activeTabId
            }
        val startsNewNavigation =
            pendingMatch != null || (previousDocument == null && ownerTabId != null && ownerTabId == activeTabId)
        val documentId =
            when {
                pendingMatch != null -> pendingMatch.documentId
                !startsNewNavigation && ownerTabId != null && previousDocument?.tabId == ownerTabId ->
                    previousDocument.documentId
                ownerTabId != null -> UUID.randomUUID().toString()
                else -> null
            }

        if (pendingMatch != null) {
            pendingNavigationTabs.removeAll {
                it.url == pendingMatch.url && it.tabId == pendingMatch.tabId &&
                    it.documentId == pendingMatch.documentId
            }
        }

        val association =
            if (ownerTabId != null && documentId != null) PageDocumentAssociation(ownerTabId, documentId) else null
        if (association != null) {
            if (startsNewNavigation) {
                pageDocuments.entries.removeAll { it.value.tabId == ownerTabId }
                documentOwners.entries.removeAll { it.value.tabId == ownerTabId }
            }
            pageDocuments[url] = association
            documentOwners[documentId] = association
            lastBoundDocument = association
        }
        return PageStartAssociation(ownerTabId, startsNewNavigation, documentId)
    }

    /**
     * WebView reports pushState, replaceState and redirect URL changes here. Reuse the
     * association of the previous URL; never assign an unknown URL to the active tab.
     */
    @Synchronized
    fun onPageUrlChanged(
        previousUrl: String?,
        url: String,
    ): PageDocumentAssociation? {
        val association =
            previousUrl?.let(pageDocuments::get)
                ?: pendingNavigationTabs.lastOrNull { it.url == url }?.let {
                    PageDocumentAssociation(it.tabId, it.documentId)
                }
                ?: pageDocuments[url]
        if (association != null) {
            pageDocuments[url] = association
            documentOwners[association.documentId] = association
            lastBoundDocument = association
        }
        return association
    }

    @Synchronized
    fun associationForDocument(documentId: String?): PageDocumentAssociation? =
        documentId?.let(documentOwners::get)

    @Synchronized
    fun associationForPage(url: String?): PageDocumentAssociation? = url?.let(pageDocuments::get)

    @Synchronized
    fun onPageFinished(url: String): String? = pageDocuments[url]?.tabId

    @Synchronized
    fun tabIdForPage(url: String?): String? = associationForPage(url)?.tabId

    private companion object {
        const val MAX_PAGE_MAPPINGS = 256
        const val MAX_PENDING_NAVIGATIONS = 64
    }
}
