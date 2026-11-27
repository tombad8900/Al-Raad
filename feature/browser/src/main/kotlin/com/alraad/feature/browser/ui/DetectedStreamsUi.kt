package com.alraad.feature.browser.ui

import com.alraad.core.common.contracts.StreamUrl

/** A detected media link tied to the page and browser tab that requested it. */
data class DetectedStreamItem(
    val stream: StreamUrl,
    val sourcePageUrl: String,
    val sourceTabId: String,
)

/** Detected-stream actions for the current page, kept as one parameter for detekt limits. */
data class DetectedStreamsUi(
    val streams: List<DetectedStreamItem> = emptyList(),
    val onPlay: (DetectedStreamItem) -> Unit = {},
    val onCopy: (DetectedStreamItem) -> Unit = {},
    val onSave: (DetectedStreamItem, String) -> Unit = { _, _ -> },
)
