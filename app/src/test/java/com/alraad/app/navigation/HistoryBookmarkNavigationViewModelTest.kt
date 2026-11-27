package com.alraad.app.navigation

import com.alraad.feature.browser.tabs.TabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryBookmarkNavigationViewModelTest {
    @Test
    fun `opens exact selected url in a new active tab before navigation`() {
        val tabs = TabManager()
        val existing = tabs.openTab("https://existing.example/")
        val viewModel = HistoryBookmarkNavigationViewModel(tabs)
        var navigated = false

        val opened = viewModel.open(" https://saved.example/watch?id=7 ") {
            assertEquals("https://saved.example/watch?id=7", tabs.state.value.tabs.last().url)
            assertEquals(tabs.state.value.tabs.last().id, tabs.state.value.activeTabId)
            navigated = true
        }

        assertTrue(opened)
        assertTrue(navigated)
        assertEquals(2, tabs.state.value.tabs.size)
        assertEquals(existing.id, tabs.state.value.tabs.first().id)
        assertEquals("https://existing.example/", tabs.state.value.tabs.first().url)
    }

    @Test
    fun `invalid urls do not open tabs or navigate`() {
        val tabs = TabManager()
        tabs.openTab("https://existing.example/")
        val viewModel = HistoryBookmarkNavigationViewModel(tabs)
        var navigated = false

        listOf("", "  ", "about:blank", "file:///tmp/page", "https://").forEach { url ->
            assertFalse(viewModel.open(url) { navigated = true })
        }

        assertFalse(navigated)
        assertEquals(1, tabs.state.value.tabs.size)
        assertEquals("https://existing.example/", tabs.state.value.tabs.single().url)
    }
}
