package com.alraad.feature.browser.ui

import com.alraad.core.common.contracts.StreamUrl

/** One detected media URL together with the exact page and tab that requested it. */
data class DetectedStreamItem(
    val stream: StreamUrl,
    val sourcePageUrl: String,
    val sourceTabId: String,
)

/** Stream picker callbacks are grouped to keep the composable API small. */
data class DetectedStreamsUi(
    val streams: List<DetectedStreamItem> = emptyList(),
    val onPlay: (DetectedStreamItem) -> Unit = {},
    val onCopy: (DetectedStreamItem) -> Unit = {},
    val onSave: (DetectedStreamItem, String) -> Unit = { _, _ -> },
)

/** Distinct URLs, ordered consistently by format and then URL. */
internal fun sortDetectedStreamItems(items: List<DetectedStreamItem>): List<DetectedStreamItem> =
    items.distinctBy { it.stream.url }.sortedWith(compareBy({ it.stream.format.name }, { it.stream.url }))
