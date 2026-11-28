package com.alraad.feature.player.impl

import com.alraad.core.common.contracts.SubtitleTrack

private const val MIME_VTT = "text/vtt"
private const val MIME_SUBRIP = "application/x-subrip"
private const val MIME_SSA = "text/x-ssa"

/**
 * Builds a [SubtitleTrack] for a user-picked local subtitle file (content:// or file://).
 * Local files never carry request headers: no page credential can ride onto them.
 * Returns null for unsupported extensions (only vtt/srt/ass/ssa are playable).
 */
fun externalSubtitleTrack(
    uri: String,
    displayName: String?,
): SubtitleTrack? {
    val extension = subtitleExtension(displayName) ?: subtitleExtension(uri) ?: return null
    val mimeType =
        when (extension) {
            "vtt" -> MIME_VTT
            "srt" -> MIME_SUBRIP
            else -> MIME_SSA
        }
    return SubtitleTrack(
        id = "external:$uri",
        url = uri,
        mimeType = mimeType,
        language = null,
        label = displayName?.substringAfterLast('/') ?: uri.substringAfterLast('/'),
        sourcePageUrl = "",
        sourceTabId = "",
    )
}

private fun subtitleExtension(name: String?): String? {
    val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return ext.takeIf { it == "vtt" || it == "srt" || it == "ass" || it == "ssa" }
}
