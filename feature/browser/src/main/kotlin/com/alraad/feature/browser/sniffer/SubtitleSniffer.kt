package com.alraad.feature.browser.sniffer

import com.alraad.core.common.contracts.SubtitleTrack
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Captures page/tab-scoped external subtitles without retaining credential headers. */
@Singleton
class SubtitleSniffer @Inject constructor() {
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

    /** Emits every observation so safe metadata can refresh, returning only new tracks. */
    fun onRequestObserved(
        url: String,
        headers: Map<String, String>,
        pageUrl: String?,
        tabId: String?,
    ): SubtitleTrack? {
        val page = pageUrl?.takeIf(::isHttpUrl) ?: return null
        val tab = tabId?.takeIf(String::isNotBlank) ?: return null
        val uri = parseUri(url) ?: return null
        if (!isHttpUrl(url)) return null
        val mimeType = mimeTypeFor(uri) ?: return null
        val language = detectLanguage(uri)
        val fileLabel = uri.path.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[._-]+"), " ").trim()
        val track = SubtitleTrack(
            id = trackId(tab, page, url),
            url = url,
            mimeType = mimeType,
            language = language,
            label = fileLabel.takeIf(String::isNotBlank) ?: language ?: "Subtitle",
            sourcePageUrl = page,
            sourceTabId = tab,
            requestHeaders = safeRequestHeaders(headers),
        )
        val isNew = synchronized(seen) {
            val existed = seen.containsKey(track.id)
            seen[track.id] = Unit
            while (seen.size > MAX_TRACKS) seen.remove(seen.entries.first().key)
            !existed
        }
        listeners.forEach { it.onSubtitleObserved(track) }
        return track.takeIf { isNew }
    }

    private fun mimeTypeFor(uri: URI): String? =
        when (uri.path.substringAfterLast('.', "").lowercase()) {
            "vtt" -> MIME_VTT
            "srt" -> MIME_SUBRIP
            "ass", "ssa" -> MIME_SSA
            else -> null
        }

    private fun detectLanguage(uri: URI): String? {
        val queryLanguage = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key.lowercase() in LANGUAGE_QUERY_KEYS) {
                normalizeLanguage(decode(part.substringAfter('=', "")))
            } else {
                null
            }
        }
        if (queryLanguage != null) return queryLanguage
        val filename = uri.path.substringAfterLast('/').lowercase()
        val match = LANGUAGE_TOKEN.find(filename) ?: return null
        return normalizeLanguage(match.groupValues[1])
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
        buildMap {
            headers.forEach { (name, rawValue) ->
                val key = name.lowercase()
                if (key !in SAFE_HEADER_NAMES || rawValue.length > MAX_HEADER_VALUE_LENGTH) return@forEach
                if (rawValue.contains('\r') || rawValue.contains('\n')) return@forEach
                val value = when (key) {
                    "origin", "referer" -> originOnly(rawValue) ?: return@forEach
                    else -> rawValue
                }
                put(canonicalHeaderName(key), value)
            }
        }

    private fun originOnly(value: String): String? {
        val uri = parseUri(value) ?: return null
        if (!isHttpUrl(value)) return null
        val host = uri.host ?: return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme}://${host}${port}"
    }

    private fun canonicalHeaderName(lowercaseName: String): String =
        when (lowercaseName) {
            "user-agent" -> "User-Agent"
            "accept" -> "Accept"
            "accept-language" -> "Accept-Language"
            "cache-control" -> "Cache-Control"
            "pragma" -> "Pragma"
            "origin" -> "Origin"
            "referer" -> "Referer"
            else -> lowercaseName
        }

    private fun isHttpUrl(value: String): Boolean {
        val uri = parseUri(value) ?: return false
        return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank()
    }

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun trackId(tabId: String, pageUrl: String, url: String): String =
        buildString {
            append(tabId.length).append(':').append(tabId)
            append(pageUrl.length).append(':').append(pageUrl)
            append(url.length).append(':').append(url)
        }

    private companion object {
        const val MAX_TRACKS = 500
        const val MAX_HEADER_VALUE_LENGTH = 2048
        const val MIME_VTT = "text/vtt"
        const val MIME_SUBRIP = "application/x-subrip"
        const val MIME_SSA = "text/x-ssa"
        val SAFE_HEADER_NAMES = setOf(
            "accept",
            "accept-language",
            "cache-control",
            "pragma",
            "user-agent",
            "origin",
            "referer",
        )
        val LANGUAGE_QUERY_KEYS = setOf("lang", "language", "hl", "locale")
        val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[a-z]{2})?")
        val LANGUAGE_TOKEN = Regex("(?:^|[._-])(ar|ara|arabic|en|eng|english|fr|fra|fre|french)(?:[._-]|$)")
    }
}
