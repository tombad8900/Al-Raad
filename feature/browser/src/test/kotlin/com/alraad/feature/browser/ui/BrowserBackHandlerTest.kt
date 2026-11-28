package com.alraad.feature.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBackHandlerTest {
    @Test
    fun walksBackThroughMultiplePagesBeforeReturningToSearch() {
        val history = mutableListOf("page-a", "page-b", "page-c")
        var current = 2
        var returnedToSearch = false

        repeat(2) {
            val result = handleBrowserBack(
                tabUrl = history[current],
                canGoBack = { current > 0 },
                goBack = { current-- },
                returnToSearch = { returnedToSearch = true },
            )
            assertEquals(BrowserBackResult.HISTORY, result)
        }

        assertEquals(0, current)
        assertFalse(returnedToSearch)
        assertEquals(
            BrowserBackResult.SEARCH,
            handleBrowserBack(
                tabUrl = history[current],
                canGoBack = { false },
                goBack = {},
                returnToSearch = { returnedToSearch = true },
            ),
        )
        assertTrue(returnedToSearch)
    }

    @Test
    fun switchingTabsMovesOnlyTheActiveTabsHistory() {
        val histories = mutableMapOf("first" to 2, "second" to 1)
        var activeTab = "second"

        handleBrowserBack(
            tabUrl = "second-page",
            canGoBack = { histories.getValue(activeTab) > 0 },
            goBack = { histories[activeTab] = histories.getValue(activeTab) - 1 },
            returnToSearch = {},
        )
        assertEquals(2, histories["first"])
        assertEquals(0, histories["second"])

        activeTab = "first"
        handleBrowserBack(
            tabUrl = "first-page",
            canGoBack = { histories.getValue(activeTab) > 0 },
            goBack = { histories[activeTab] = histories.getValue(activeTab) - 1 },
            returnToSearch = {},
        )
        assertEquals(1, histories["first"])
        assertEquals(0, histories["second"])
    }

    @Test
    fun emptyHistoryReturnsToSearchAndSearchBackStaysWithoutExiting() {
        var searchVisible = false
        var exitRequested = false
        val result = handleBrowserBack(
            tabUrl = "https://first-page.example",
            canGoBack = { false },
            goBack = {},
            returnToSearch = { searchVisible = true },
        )
        assertEquals(BrowserBackResult.SEARCH, result)
        assertTrue(searchVisible)

        val searchResult = handleBrowserBack(
            tabUrl = "",
            canGoBack = { false },
            goBack = {},
            returnToSearch = { searchVisible = false },
        )
        assertEquals(BrowserBackResult.STAY, searchResult)
        assertTrue(searchVisible)
        assertFalse(exitRequested)
    }
}
