package com.alraad.feature.browser.ui

import com.alraad.core.common.contracts.StreamUrl

/** One media URL with the page and tab that originated its request. */
data class DetectedStreamItem(
    val stream: StreamUrl,
    val sourcePageUrl: String,
    val sourceTabId: String,
    val documentId: String? = null,
)

/** Callbacks for the current-page stream picker. */
data class DetectedStreamsUi(
    val streams: List<DetectedStreamItem> = emptyList(),
    val onPlay: (DetectedStreamItem) -> Unit = {},
    val onCopy: (DetectedStreamItem) -> Unit = {},
    val onSave: (DetectedStreamItem, String) -> Unit = { _, _ -> },
)

/** Unique URLs sorted by format and URL for stable, predictable selection. */
internal fun sortDetectedStreamItems(items: List<DetectedStreamItem>): List<DetectedStreamItem> =
    items.distinctBy { it.stream.url }.sortedWith(compareBy({ it.stream.format.name }, { it.stream.url }))
