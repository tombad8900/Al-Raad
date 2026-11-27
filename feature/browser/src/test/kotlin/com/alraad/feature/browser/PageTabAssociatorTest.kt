package com.alraad.feature.browser

import com.alraad.feature.browser.ui.PageTabAssociator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTabAssociatorTest {
    @Test
    fun `requested page binds finish to its requesting tab`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        assertEquals("tabA", associator.onPageStarted("https://a.example/", "tabA"))
        assertEquals("tabA", associator.onPageFinished("https://a.example/"))
    }

    @Test
    fun `finish after switching tabs still updates the originating tab`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        associator.onPageStarted("https://a.example/", "tabA")
        associator.recordRequest("tabB", "https://b.example/")
        associator.onPageStarted("https://b.example/", "tabB")
        assertEquals("tabA", associator.onPageFinished("https://a.example/"))
        assertEquals("tabB", associator.onPageFinished("https://b.example/"))
    }

    @Test
    fun `late redirect of a displaced chain keeps its own tab and never steals the pending request`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        associator.onPageStarted("https://a.example/", "tabA")
        // User switches to tab B; B's load is queued but has not started yet.
        associator.recordRequest("tabB", "https://b.example/")
        // A's redirect arrives late: it must bind to A and leave B's request intact.
        assertEquals("tabA", associator.onPageStarted("https://cdn.a.example/", "tabB"))
        assertEquals("tabB", associator.onPageStarted("https://b.example/", "tabB"))
        assertEquals("tabA", associator.onPageFinished("https://cdn.a.example/"))
        assertEquals("tabB", associator.onPageFinished("https://b.example/"))
    }

    @Test
    fun `in-page navigation with no pending request binds to the active tab`() {
        val associator = PageTabAssociator()
        assertEquals("tabA", associator.onPageStarted("https://link.example/", "tabA"))
        assertEquals("tabA", associator.onPageFinished("https://link.example/"))
    }

    @Test
    fun `finish without a bound start is uncorrelated and dropped`() {
        val associator = PageTabAssociator()
        assertNull(associator.onPageFinished("https://unknown.example/"))
    }

    @Test
    fun `repeated identical request does not reload`() {
        val associator = PageTabAssociator()
        org.junit.Assert.assertTrue(associator.shouldLoad("tabA", "https://a.example/", null))
        associator.recordRequest("tabA", "https://a.example/")
        org.junit.Assert.assertFalse(associator.shouldLoad("tabA", "https://a.example/", null))
    }

    @Test
    fun `redirect in the same active tab preserves the current navigation chain`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        associator.onPageStarted("https://a.example/", "tabA")

        val redirect = associator.onPageStartedWithContext("https://cdn.a.example/", "tabA")

        assertEquals("tabA", redirect.tabId)
        org.junit.Assert.assertFalse(redirect.startsNewNavigation)
        assertEquals("tabA", associator.tabIdForPage("https://a.example/"))
        assertEquals("tabA", associator.tabIdForPage("https://cdn.a.example/"))
    }

    @Test
    fun `in-page navigation request starts a new page scope`() {
        val associator = PageTabAssociator()
        associator.onPageStarted("https://a.example/", "tabA")
        associator.recordRequest("tabA", "https://a.example/next")

        val next = associator.onPageStartedWithContext("https://a.example/next", "tabA")

        assertEquals("tabA", next.tabId)
        org.junit.Assert.assertTrue(next.startsNewNavigation)
        assertNull(associator.tabIdForPage("https://a.example/"))
        assertEquals("tabA", associator.tabIdForPage("https://a.example/next"))
    }

    @Test
    fun `page tab mapping survives finish for late subresource requests`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        associator.onPageStarted("https://a.example/", "tabA")

        assertEquals("tabA", associator.onPageFinished("https://a.example/"))
        assertEquals("tabA", associator.tabIdForPage("https://a.example/"))
    }

    @Test
    fun `new navigation retires previous page mappings for the same tab`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        associator.onPageStarted("https://a.example/", "tabA")
        associator.onPageFinished("https://a.example/")

        associator.recordRequest("tabA", "https://next.example/")

        assertNull(associator.tabIdForPage("https://a.example/"))
        assertEquals("tabA", associator.onPageStarted("https://next.example/", "tabA"))
        assertEquals("tabA", associator.tabIdForPage("https://next.example/"))
    }

    @Test
    fun `page mapping cache remains bounded`() {
        val associator = PageTabAssociator()
        repeat(300) { index ->
            val tabId = "tab$index"
            val url = "https://page$index.example/"
            associator.recordRequest(tabId, url)
            associator.onPageStarted(url, tabId)
        }

        assertNull(associator.tabIdForPage("https://page0.example/"))
        assertEquals("tab299", associator.tabIdForPage("https://page299.example/"))
    }

}
