package com.alraad.feature.browser.ui

/** Everything the browser screen surfaces upward: detected-stream handoff plus visit/bookmark actions. */
data class BrowserHandlers(
    val detectedStreams: DetectedStreamsUi = DetectedStreamsUi(),
    val actions: BrowserActions = BrowserActions(),
)
