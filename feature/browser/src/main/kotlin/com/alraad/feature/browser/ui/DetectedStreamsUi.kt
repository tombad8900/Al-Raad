package com.alraad.feature.browser.ui

import com.alraad.core.common.contracts.StreamUrl

/** Detected-stream list plus the play action, kept as one param for detekt limits. */
data class DetectedStreamsUi(
    val streams: List<StreamUrl> = emptyList(),
    val onPlay: (StreamUrl) -> Unit = {},
    val onCopy: (StreamUrl) -> Unit = {},
    val onSave: (StreamUrl) -> Unit = {},
)
