package com.alraad.feature.browser

import com.alraad.feature.browser.tabs.TabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabManagerTest {
    @Test fun `open makes tab active, close moves active to last remaining`() {
        val manager = TabManager()
        val first = manager.openTab("https://a.example.com")
        val second = manager.openTab("https://b.example.com")
        assertEquals(second.id, manager.state.value.activeTabId)
        manager.closeTab(second.id)
        assertEquals(first.id, manager.state.value.activeTabId)
        manager.closeTab(first.id)
        assertNull(manager.state.value.activeTabId)
        assertEquals(0, manager.state.value.tabs.size)
    }

    @Test fun `switch ignores unknown ids and update patches fields`() {
        val manager = TabManager()
        val tab = manager.openTab()
        manager.switchTo("no-such-id")
        assertEquals(tab.id, manager.state.value.activeTabId)
        manager.updateTab(tab.id, url = "https://x.example.com", title = "X")
        assertEquals("https://x.example.com", manager.state.value.tabs[0].url)
        assertEquals("X", manager.state.value.tabs[0].title)
    }
}
