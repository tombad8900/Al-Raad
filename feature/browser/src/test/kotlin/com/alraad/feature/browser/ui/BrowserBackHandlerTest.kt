package com.alraad.feature.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBackHandlerTest {
    @Test
    fun activeTabHistoryEndsAtSearchAndOtherTabHistoryStaysIndependent() {
        val history = mutableMapOf("first" to 1, "second" to 2)
        var active = "second"
        var search = false

        fun back(url: String) =
            handleBrowserBack(
                tabUrl = url,
                canGoBack = { history.getValue(active) > 0 },
                goBack = { history[active] = history.getValue(active) - 1 },
                returnToSearch = { search = true },
            )
        assertEquals(BrowserBackResult.HISTORY, back("second-c"))
        assertEquals(BrowserBackResult.HISTORY, back("second-b"))
        assertEquals(1, history["first"])
        assertEquals(BrowserBackResult.SEARCH, back("second-a"))
        assertTrue(search)
        active = "first"
        assertEquals(BrowserBackResult.HISTORY, back("first-b"))
        assertEquals(0, history["first"])
        assertEquals(0, history["second"])
        assertEquals(
            BrowserBackResult.STAY,
            handleBrowserBack("", { false }, {}, { error("must stay") }),
        )
    }
}
