package com.alraad.core.common.contracts

/**
 * Frozen stream contracts (Issue #1). Do not change without owner approval.
 */

enum class StreamFormat { HLS, DASH, MP4, WEBM, MKV, UNKNOWN }

enum class StreamSource { NETWORK, JS_BRIDGE, DOM }

data class StreamUrl(
    val url: String,
    val format: StreamFormat,
    val source: StreamSource,
    val headers: Map<String, String> = emptyMap(),
    val size: Long? = null,
    // Detected standard EME/Widevine license request for this stream, when observed.
    val drmLicenseUrl: String? = null,
    val drmLicenseRequestHeaders: Map<String, String> = emptyMap(),
)

data class SavedStream(
    val id: Long = 0,
    val title: String,
    val stream: StreamUrl,
    val thumbnailUrl: String? = null,
    val sourcePageUrl: String? = null,
    val createdAt: Long,
)

/** A page/tab-scoped external subtitle captured from a WebView request. */
data class SubtitleTrack(
    val id: String,
    val url: String,
    val mimeType: String,
    val language: String?,
    val label: String,
    val sourcePageUrl: String,
    val sourceTabId: String,
    /** Allowlisted, non-credential headers scoped only to this subtitle URL. */
    val requestHeaders: Map<String, String> = emptyMap(),
)

/** Immutable playback selection and only the subtitles belonging to its page/tab. */
data class PlaybackRequest(
    val sources: List<StreamUrl>,
    val selectedSourceIndex: Int = 0,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val sourcePageUrl: String? = null,
    val sourceTabId: String? = null,
) {
    init {
        require(sources.isNotEmpty()) { "PlaybackRequest requires at least one source" }
        require(selectedSourceIndex in sources.indices) { "Selected source index is out of range" }
    }

    val selectedStream: StreamUrl
        get() = sources[selectedSourceIndex]

    fun selectSource(index: Int): PlaybackRequest = copy(selectedSourceIndex = index)

    companion object {
        fun single(stream: StreamUrl): PlaybackRequest = PlaybackRequest(sources = listOf(stream))
    }
}

interface StreamListener {
    fun onStreamDetected(stream: StreamUrl)

    fun onStreamUpdated(stream: StreamUrl) {
    }
}

interface AdBlocker {
    suspend fun shouldBlock(url: String): Boolean

    /**
     * Context-aware decision. [sourceUrl] is the page that issued the request ("" when unknown) and
     * [resourceType] is an adblock request type (document, subdocument, script, image, stylesheet,
     * xmlhttprequest, media, font, websocket, other). Engines that only know hosts keep this default;
     * rule engines must override it and must not rely on the URL-only overload.
     */
    suspend fun shouldBlock(
        url: String,
        sourceUrl: String,
        resourceType: String,
    ): Boolean = shouldBlock(url)

    suspend fun cosmeticCss(host: String): String
}

interface PlayerController {
    fun play(stream: StreamUrl)

    /** Rich page-scoped request; legacy implementations remain source-compatible. */
    fun play(request: PlaybackRequest) {
        play(request.selectedStream)
    }

    fun pause()

    fun seekTo(ms: Long)

    /**
     * Frees the underlying player resources but PRESERVES the current stream
     * selection, so the player route can rebuild after an activity recreate or a
     * back-stack restore. Use [stop] to end the session and clear the selection.
     */
    fun release()

    /** Ends playback: releases resources and clears the stream selection. */
    fun stop()
}
