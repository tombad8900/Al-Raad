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

interface StreamListener {
    fun onStreamDetected(stream: StreamUrl)

    fun onStreamUpdated(stream: StreamUrl) {
    }
}

interface AdBlocker {
    suspend fun shouldBlock(url: String): Boolean

    fun cosmeticCss(host: String): String
}

interface PlayerController {
    fun play(stream: StreamUrl)

    fun pause()

    fun seekTo(ms: Long)

    fun release()
}
