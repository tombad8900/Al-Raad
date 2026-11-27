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
}
