package com.alraad.feature.browser.ui

/** User-action callbacks out of the browser screen (visit recording, bookmarking). */
data class BrowserActions(
    val onPageVisited: (url: String, title: String, tabId: String?) -> Unit = { _, _, _ -> },
    val onBookmark: () -> Unit = {},
)
