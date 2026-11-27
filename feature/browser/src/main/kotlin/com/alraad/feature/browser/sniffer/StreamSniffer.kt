package com.alraad.feature.browser.sniffer

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamListener
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects media streams from observed requests (WebView network, JS bridge, DOM).
 * Captures the full request headers verbatim (needed for later playback), the
 * Content-Length size when present, and dedups repeated observations of the same
 * URL+headers pair. Every detection is logged raw and unredacted.
 */
private const val MAX_SEEN_KEYS = 1000

@Singleton
class StreamSniffer
    @Inject
    constructor(
        private val logger: DiagnosticsLogger,
    ) {
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<StreamListener>()

        @Volatile
        private var lastDetected: StreamUrl? = null

        // Most recent detected stream per page URL, so a license request attaches to
        // the stream of the document that issued it, not a stream from another tab/page.
        private val lastDetectedByPage =
            java.util.Collections.synchronizedMap(
                object : LinkedHashMap<String, StreamUrl>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamUrl>?): Boolean = size > MAX_SEEN_KEYS
                },
            )

        // Detectable standard EME/Widevine license endpoint hints (case-insensitive).
        // Deliberately excludes a bare "drm" substring: media URLs like /drm/manifest.mpd
        // must classify as streams via StreamFormatDetector, never as license requests.
        private val licenseUrlHints = listOf("widevine", "playready", "clearkey", "/license", "licensekey", "acquirelicense")

        fun isLicenseRequest(url: String): Boolean {
            val lower = url.lowercase()
            return licenseUrlHints.any { lower.contains(it) }
        }

        /**
         * Records a detected license request and attaches it to the stream detected on
         * the same page when [pageUrl] is known, falling back to the most recent stream
         * overall. License-request headers stay distinct from stream headers.
         */
        fun onDrmLicenseObserved(
            url: String,
            headers: Map<String, String> = emptyMap(),
            pageUrl: String? = null,
            tabId: String? = null,
        ) {
            if (!isLicenseRequest(url)) return
            logger.log(
                DiagCategory.SNIFFER,
                DiagLevel.INFO,
                "DRM license request detected",
                mapOf("url" to url, "headers" to headers.toString()),
            )
            // Correlate strictly by page: when the page is known, only that page's stream
            // may take the license; a license from a page with no detected stream stays
            // unattached rather than landing on another page's stream.
            val target =
                if (pageUrl != null) {
                    lastDetectedByPage[pageUrl]
                } else {
                    lastDetected
                }
            target?.let { found ->
                val updated = found.copy(drmLicenseUrl = url, drmLicenseRequestHeaders = headers)
                if (lastDetected?.url == found.url) lastDetected = updated
                pageUrl?.let { lastDetectedByPage[it] = updated }
                listeners.forEach {
                    it.onStreamUpdated(updated)
                    it.onStreamObserved(updated, pageUrl, tabId)
                }
            }
        }

        // Access-order LRU, bounded: long sessions evict the oldest unique keys.
        private val seen =
            java.util.Collections.synchronizedMap(
                object : LinkedHashMap<String, Long>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_SEEN_KEYS
                },
            )

        // Length-prefixed canonical encoding: unambiguous even when header values
        // contain separators like '=' or ';'.
        private fun dedupKey(
            url: String,
            headers: Map<String, String>,
        ): String =
            buildString {
                append(url)
                for (entry in headers.entries.sortedBy { it.key }) {
                    append('|').append(entry.key.length).append(':').append(entry.key)
                    append('=').append(entry.value.length).append(':').append(entry.value)
                }
            }

        fun registerListener(listener: StreamListener) {
            listeners += listener
        }

        fun unregisterListener(listener: StreamListener) {
            listeners -= listener
        }

        fun onRequestObserved(
            url: String,
            headers: Map<String, String> = emptyMap(),
            contentType: String? = null,
            source: StreamSource = StreamSource.NETWORK,
            pageUrl: String? = null,
            tabId: String? = null,
        ): StreamUrl? {
            if (StreamFormatDetector.isStream(url, contentType)) {
                return detectStream(url, headers, contentType, source, pageUrl, tabId)
            }
            if (isLicenseRequest(url)) {
                onDrmLicenseObserved(url, headers, pageUrl, tabId)
            }
            return null
        }

        private fun detectStream(
            url: String,
            headers: Map<String, String>,
            contentType: String?,
            source: StreamSource,
            pageUrl: String? = null,
            tabId: String? = null,
        ): StreamUrl? {
            val detected = StreamUrl(
                url = url,
                format = StreamFormatDetector.detect(url, contentType),
                source = source,
                headers = headers,
                size =
                    headers.entries
                        .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                        ?.value
                        ?.toLongOrNull(),
            )
            val isDuplicate = seen.put(dedupKey(url, headers), System.currentTimeMillis()) != null
            lastDetected = detected
            pageUrl?.let { lastDetectedByPage[it] = detected }
            if (!isDuplicate) {
                logger.log(
                    DiagCategory.SNIFFER,
                    DiagLevel.INFO,
                    "Stream detected",
                    mapOf(
                        "url" to detected.url,
                        "format" to detected.format.name,
                        "source" to source.name,
                        "headers" to headers.toString(),
                    ),
                )
                listeners.forEach { it.onStreamDetected(detected) }
            }
            listeners.forEach { it.onStreamObserved(detected, pageUrl, tabId) }
            return if (isDuplicate) null else detected
        }
    }
