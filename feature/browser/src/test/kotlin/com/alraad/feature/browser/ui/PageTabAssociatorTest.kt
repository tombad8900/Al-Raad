package com.alraad.feature.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PageTabAssociatorTest {
    @Test
    fun restoresCurrentDocumentOwnershipAfterBrowserScreenRecreation() {
        val associator = PageTabAssociator()
        val currentUrl = "https://example.com/watch"

        associator.restoreCurrentPage("retained-tab", currentUrl)

        assertEquals("retained-tab", associator.tabIdForPage(currentUrl))
        assertEquals("retained-tab", associator.onPageFinished(currentUrl))

        val redirect = associator.onPageStartedWithContext(
            "https://cdn.example.com/watch",
            "different-active-tab",
        )
        assertEquals("retained-tab", redirect.tabId)
        assertFalse(redirect.startsNewNavigation)
    }
}
