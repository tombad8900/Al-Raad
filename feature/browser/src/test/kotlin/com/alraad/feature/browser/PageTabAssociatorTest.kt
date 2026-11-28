package com.alraad.feature.browser

import com.alraad.feature.browser.ui.PageTabAssociator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTabAssociatorTest {
    @Test
    fun `redirect chain remains bound to its source tab after switching tabs`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        assertTrue(associator.onPageStartedWithContext("https://a.example/", "tabA").startsNewNavigation)
        associator.recordRequest("tabB", "https://b.example/")

        val redirect = associator.onPageStartedWithContext("https://cdn.a.example/", "tabB")
        assertEquals("tabA", redirect.tabId)
        assertFalse(redirect.startsNewNavigation)
        assertEquals("tabB", associator.onPageStartedWithContext("https://b.example/", "tabB").tabId)
        assertEquals("tabA", associator.onPageFinished("https://cdn.a.example/"))
        assertEquals("tabA", associator.tabIdForPage("https://cdn.a.example/"))
    }

    @Test
    fun `pending request resolves resource ownership before page start callback`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")

        assertEquals("tabA", associator.tabIdForPage("https://a.example/"))
        assertEquals("tabA", associator.onPageStartedWithContext("https://a.example/", "tabA").tabId)
        assertEquals("tabA", associator.tabIdForPage("https://a.example/"))
    }

    @Test
    fun `pending tab takes precedence over an older mapping for the same page URL`() {
        val associator = PageTabAssociator()
        val page = "https://same.example/watch"
        associator.recordRequest("tabA", page)
        associator.onPageStartedWithContext(page, "tabA")
        associator.recordRequest("tabB", page)

        assertEquals("tabB", associator.tabIdForPage(page))
        assertEquals("tabB", associator.onPageStartedWithContext(page, "tabB").tabId)
    }

    @Test
    fun `new request retires stale pending ownership for the same tab`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        associator.recordRequest("tabA", "https://a.example/next")

        assertNull(associator.tabIdForPage("https://a.example/"))
        assertEquals("tabA", associator.tabIdForPage("https://a.example/next"))
    }

    @Test
    fun `new navigation retires previous page mapping`() {
        val associator = PageTabAssociator()
        associator.recordRequest("tabA", "https://a.example/")
        associator.onPageStartedWithContext("https://a.example/", "tabA")
        associator.recordRequest("tabA", "https://a.example/next")

        val next = associator.onPageStartedWithContext("https://a.example/next", "tabA")

        assertEquals("tabA", next.tabId)
        assertTrue(next.startsNewNavigation)
        assertNull(associator.tabIdForPage("https://a.example/"))
        assertEquals("tabA", associator.tabIdForPage("https://a.example/next"))
    }

    @Test
    fun `unknown pages are dropped and mapping cache is bounded`() {
        val associator = PageTabAssociator()
        assertNull(associator.onPageFinished("https://unknown.example/"))
        repeat(300) { index ->
            val tabId = "tab$index"
            val url = "https://page$index.example/"
            associator.recordRequest(tabId, url)
            associator.onPageStartedWithContext(url, tabId)
        }
        assertNull(associator.tabIdForPage("https://page0.example/"))
        assertEquals("tab299", associator.tabIdForPage("https://page299.example/"))
    }

    @Test
    fun `closed tab state is removed before a new tab navigation`() {
        val associator = PageTabAssociator()
        associator.recordRequest("closedTab", "https://closed.example/")
        associator.onPageStartedWithContext("https://closed.example/", "closedTab")
        associator.recordRequest("closedTab", "https://closed.example/next")

        associator.retainTabs(setOf("liveTab"))
        val livePage = associator.onPageStartedWithContext("https://live.example/", "liveTab")

        assertEquals("liveTab", livePage.tabId)
        assertTrue(livePage.startsNewNavigation)
        assertNull(associator.tabIdForPage("https://closed.example/"))
        assertNull(associator.tabIdForPage("https://closed.example/next"))
    }
}
