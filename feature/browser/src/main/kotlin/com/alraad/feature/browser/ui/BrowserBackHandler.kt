package com.alraad.feature.browser.ui

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
