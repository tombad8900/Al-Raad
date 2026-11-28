package com.alraad.feature.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBackHandlerTest {
    @Test
    fun multiplePagesReturnToSearchOnlyAfterTheActiveHistoryEnds() {
        val history = mutableListOf("a", "b", "c")
        var index = 2
        var search = false
        repeat(2) {
            assertEquals(
                BrowserBackResult.HISTORY,
                handleBrowserBack(history[index], { index > 0 }, { index-- }, { search = true }),
            )
        }
        assertEquals(0, index)
        assertEquals(
            BrowserBackResult.SEARCH,
            handleBrowserBack(history[index], { false }, {}, { search = true }),
        )
        assertTrue(search)
    }

    @Test
    fun switchingTabsUsesOnlyTheSelectedHistoryAndSearchBackStays() {
        val histories = mutableMapOf("a" to 2, "b" to 1)
        var active = "b"
        fun back(url: String) = handleBrowserBack(
            url,
            { histories.getValue(active) > 0 },
            { histories[active] = histories.getValue(active) - 1 },
            {},
        )
        back("b-page")
        assertEquals(2, histories["a"])
        assertEquals(0, histories["b"])
        active = "a"
        back("a-page")
        assertEquals(1, histories["a"])
        assertEquals(0, histories["b"])
        assertEquals(BrowserBackResult.STAY, handleBrowserBack("", { false }, {}, { error("search must stay") }))
    }
}
