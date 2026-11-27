package com.alraad.app.navigation

import com.alraad.feature.browser.tabs.TabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBrowserNavigationViewModelTest {
    @Test
    fun `source page opens in a new active browser tab and requests browser navigation`() {
        val tabs = TabManager()
        val existing = tabs.openTab("https://already-open.example/")
        val viewModel = LibraryBrowserNavigationViewModel(tabs)
        var navigationRequests = 0

        val opened =
            viewModel.openSourcePage("https://publisher.example/watch/episode?id=7") {
                navigationRequests += 1
            }

        assertTrue(opened)
        assertEquals(2, tabs.state.value.tabs.size)
        val sourceTab = tabs.state.value.tabs.last()
        assertEquals("https://publisher.example/watch/episode?id=7", sourceTab.url)
        assertEquals(sourceTab.id, tabs.state.value.activeTabId)
        assertEquals(existing.id, tabs.state.value.tabs.first().id)
        assertEquals(1, navigationRequests)
    }

    @Test
    fun `missing or invalid source page does not create a tab or navigate`() {
        val tabs = TabManager()
        val viewModel = LibraryBrowserNavigationViewModel(tabs)
        var navigationRequests = 0
        val navigate = { navigationRequests += 1 }

        assertFalse(viewModel.openSourcePage(null, navigate))
        assertFalse(viewModel.openSourcePage("   ", navigate))
        assertFalse(viewModel.openSourcePage("https://", navigate))
        assertFalse(viewModel.openSourcePage("file:///private/source.html", navigate))

        assertTrue(tabs.state.value.tabs.isEmpty())
        assertEquals(0, navigationRequests)
    }
}
