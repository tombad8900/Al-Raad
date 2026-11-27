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
}
