package com.alraad.feature.browser.ui

import com.alraad.core.common.contracts.StreamUrl

/** Detected-stream list plus actions; save receives the title confirmed in the dialog. */
data class DetectedStreamsUi(
    val streams: List<StreamUrl> = emptyList(),
    val onPlay: (StreamUrl) -> Unit = {},
    val onCopy: (StreamUrl) -> Unit = {},
    val onSave: (StreamUrl, String) -> Unit = { _, _ -> },
)
