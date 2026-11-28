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
    private var loadingDocumentId: String? = null

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
        if (lastBoundDocument?.tabId !in activeTabIds) {
            lastBoundDocument = null
            loadingDocumentId = null
        }
        if (lastRequestedNavigation?.first !in activeTabIds) lastRequestedNavigation = null
    }

    @Synchronized
    fun onPageStartedWithContext(
        url: String,
        activeTabId: String?,
    ): PageStartAssociation {
        val pendingMatch = pendingNavigationTabs.lastOrNull { it.url == url }
        val pendingRedirect = if (pendingMatch == null) pendingNavigationTabs.lastOrNull() else null
        val previousDocument = lastBoundDocument
        val redirectBelongsToPrevious =
            pendingRedirect != null && previousDocument != null && previousDocument.tabId != activeTabId
        val ownerTabId =
            when {
                pendingMatch != null -> pendingMatch.tabId
                redirectBelongsToPrevious -> previousDocument?.tabId
                pendingRedirect != null -> pendingRedirect.tabId
                previousDocument != null && previousDocument.tabId != activeTabId -> previousDocument.tabId
                else -> activeTabId
            }
        val isRedirectContinuation =
            previousDocument != null && previousDocument.documentId == loadingDocumentId &&
                ownerTabId == previousDocument.tabId
        val startsNewNavigation =
            pendingMatch != null || (pendingRedirect != null && !redirectBelongsToPrevious) ||
                (ownerTabId != null && !isRedirectContinuation)
        val documentId =
            when {
                pendingMatch != null -> pendingMatch.documentId
                pendingRedirect != null && !redirectBelongsToPrevious -> pendingRedirect.documentId
                !startsNewNavigation && ownerTabId != null && previousDocument?.tabId == ownerTabId ->
                    previousDocument.documentId
                ownerTabId != null -> UUID.randomUUID().toString()
                else -> null
            }

        val resolvedNavigation =
            pendingMatch ?: pendingRedirect?.takeUnless { redirectBelongsToPrevious }
        if (resolvedNavigation != null) {
            pendingNavigationTabs.removeAll {
                it.url == resolvedNavigation.url && it.tabId == resolvedNavigation.tabId &&
                    it.documentId == resolvedNavigation.documentId
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
            loadingDocumentId = documentId
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
    fun associationForPage(url: String?): PageDocumentAssociation? =
        url?.let { pageDocuments[it] ?: pendingNavigationTabs.lastOrNull { pending -> pending.url == it }?.let { pending ->
            PageDocumentAssociation(pending.tabId, pending.documentId)
        } }

    @Synchronized
    fun onPageFinished(url: String): String? {
        val association = pageDocuments[url] ?: return null
        if (loadingDocumentId == association.documentId) loadingDocumentId = null
        return association.tabId
    }

    @Synchronized
    fun tabIdForPage(url: String?): String? = associationForPage(url)?.tabId

    private companion object {
        const val MAX_PAGE_MAPPINGS = 256
        const val MAX_PENDING_NAVIGATIONS = 64
    }
}
