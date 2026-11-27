package com.alraad.feature.browser.sniffer

import android.net.Uri
import com.alraad.core.common.contracts.SubtitleTrack
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Captures external subtitle requests without persisting or logging credential headers. */
class SubtitleSniffer {
    fun interface Listener {
        fun onSubtitleObserved(track: SubtitleTrack)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val seen = LinkedHashMap<String, Unit>(MAX_TRACKS, 0.75f, true)

    fun registerListener(listener: Listener) {
        listeners += listener
    }

    fun unregisterListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * Returns a track only for a newly seen URL; listeners receive updates as well so
     * safe request metadata can be refreshed without counting duplicate links twice.
     */
    fun onRequestObserved(
        url: String,
        headers: Map<String, String>,
        pageUrl: String?,
        tabId: String?,
    ): SubtitleTrack? {
        val page = pageUrl?.takeIf(::isHttpUrl) ?: return null
        val tab = tabId?.takeIf(String::isNotBlank) ?: return null
        val format = formatFor(url) ?: return null
        val uri = Uri.parse(url)
        if (!isHttpUrl(url)) return null

        val language = detectLanguage(uri)
        val filename = uri.lastPathSegment?.substringBeforeLast('.')?.replace(Regex("[._-]+"), " ")?.trim()
        val label = filename?.takeIf(String::isNotBlank) ?: language ?: "Subtitle"
        val track =
            SubtitleTrack(
                id = trackId(tab, page, url),
                url = url,
                mimeType = format,
                language = language,
                label = label,
                sourcePageUrl = page,
                sourceTabId = tab,
                requestHeaders = safeRequestHeaders(headers),
            )
        val key = track.id
        val isNew =
            synchronized(seen) {
                val existed = seen.containsKey(key)
                seen[key] = Unit
                while (seen.size > MAX_TRACKS) seen.remove(seen.entries.first().key)
                !existed
            }
        listeners.forEach { it.onSubtitleObserved(track) }
        return track.takeIf { isNew }
    }

    private fun formatFor(url: String): String? {
        val extension = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        return when (extension) {
            "vtt" -> MIME_VTT
            "srt" -> MIME_SUBRIP
            "ass", "ssa" -> MIME_SSA
            else -> null
        }
    }

    private fun detectLanguage(uri: Uri): String? {
        val queryLanguage =
            listOf("lang", "language", "hl", "locale")
                .firstNotNullOfOrNull { key -> uri.getQueryParameter(key) }
                ?.let(::normalizeLanguage)
        if (queryLanguage != null) return queryLanguage

        val filename = uri.lastPathSegment.orEmpty().lowercase()
        val match = LANGUAGE_TOKEN.find(filename) ?: return null
        return when (match.groupValues[1]) {
            "ara", "arabic" -> "ar"
            "eng", "english" -> "en"
            "fre", "fra", "french" -> "fr"
            else -> match.groupValues[1]
        }
    }

    private fun normalizeLanguage(value: String): String? {
        val candidate = value.trim().lowercase().replace('_', '-')
        return when (candidate) {
            "ara", "arabic" -> "ar"
            "eng", "english" -> "en"
            "fre", "fra", "french" -> "fr"
            else -> candidate.takeIf { LANGUAGE_TAG.matches(it) }
        }
    }

    private fun safeRequestHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filter { (name, value) ->
            name.lowercase() in SAFE_HEADER_NAMES &&
                value.length <= MAX_HEADER_VALUE_LENGTH &&
                !value.contains('\r') &&
                !value.contains('\n')
        }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }

    private fun trackId(
        tabId: String,
        pageUrl: String,
        url: String,
    ): String = buildString {
        append(tabId.length).append(':').append(tabId)
        append(pageUrl.length).append(':').append(pageUrl)
        append(url)
    }

    private companion object {
        const val MAX_TRACKS = 500
        const val MAX_HEADER_VALUE_LENGTH = 2048
        const val MIME_VTT = "text/vtt"
        const val MIME_SUBRIP = "application/x-subrip"
        const val MIME_SSA = "text/x-ssa"
        val SAFE_HEADER_NAMES = setOf("accept", "accept-language", "cache-control", "pragma", "user-agent")
        val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[a-z]{2})?")
        val LANGUAGE_TOKEN = Regex("(?:^|[._-])(ar|ara|arabic|en|eng|english|fr|fra|fre|french)(?:[._-]|$)")
    }
}
