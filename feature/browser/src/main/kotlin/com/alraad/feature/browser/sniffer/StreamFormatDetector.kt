package com.alraad.feature.browser.sniffer

import com.alraad.core.common.contracts.StreamFormat

/** Detects stream format from the URL path and, when available, the Content-Type header. */
object StreamFormatDetector {
    private val extensionFormats =
        mapOf(
            "m3u8" to StreamFormat.HLS,
            "mpd" to StreamFormat.DASH,
            "mp4" to StreamFormat.MP4,
            "webm" to StreamFormat.WEBM,
            "mkv" to StreamFormat.MKV,
        )

    private val mimeFormats =
        mapOf(
            "application/vnd.apple.mpegurl" to StreamFormat.HLS,
            "application/x-mpegurl" to StreamFormat.HLS,
            "application/dash+xml" to StreamFormat.DASH,
            "video/mp4" to StreamFormat.MP4,
            "video/webm" to StreamFormat.WEBM,
            "video/x-matroska" to StreamFormat.MKV,
        )

    fun detect(
        url: String,
        contentType: String? = null,
    ): StreamFormat {
        val fromMime = contentType?.substringBefore(';')?.trim()?.lowercase()?.let(mimeFormats::get)
        if (fromMime != null) return fromMime
        val path = url.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase()
        return extensionFormats[extension] ?: StreamFormat.UNKNOWN
    }

    fun isStream(
        url: String,
        contentType: String? = null,
    ): Boolean = detect(url, contentType) != StreamFormat.UNKNOWN
}
