package com.alraad.feature.browser.ui

internal enum class BrowserBackResult {
    HISTORY,
    SEARCH,
    STAY,
}

/** Applies Back to the active tab only; the search root never delegates Back to app exit. */
internal fun handleBrowserBack(
    tabUrl: String,
    canGoBack: () -> Boolean,
    goBack: () -> Unit,
    returnToSearch: () -> Unit,
): BrowserBackResult =
    when {
        canGoBack() -> {
            goBack()
            BrowserBackResult.HISTORY
        }
        tabUrl.isNotBlank() && tabUrl != "about:blank" -> {
            returnToSearch()
            BrowserBackResult.SEARCH
        }
        else -> BrowserBackResult.STAY
    }
