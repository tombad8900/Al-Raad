package com.alraad.feature.browser.ui

/** User-action callbacks out of the browser screen (visit recording, bookmarking). */
data class BrowserActions(
    val onPageVisited: (url: String, title: String) -> Unit = { _, _ -> },
    val onPageStarted: (tabId: String, url: String, startsNewNavigation: Boolean) -> Unit = { _, _, _ -> },
    val onPageFinished: (tabId: String, url: String, title: String) -> Unit = { _, _, _ -> },
    val onBookmark: () -> Unit = {},
)
