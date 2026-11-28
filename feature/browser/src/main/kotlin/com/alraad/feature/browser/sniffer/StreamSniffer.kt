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
 * Captures request headers verbatim and deduplicates repeated URL+headers pairs.
 */
private const val MAX_SEEN_KEYS = 1000

@Singleton
class StreamSniffer
    @Inject
    constructor(
        private val logger: DiagnosticsLogger,
    ) {
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<StreamListener>()
        private val observationListeners = java.util.concurrent.CopyOnWriteArrayList<StreamObservationListener>()

        @Volatile
        private var lastDetected: StreamUrl? = null

        private val lastDetectedByPageAndTab =
            java.util.Collections.synchronizedMap(
                object : LinkedHashMap<String, StreamUrl>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamUrl>?): Boolean = size > MAX_SEEN_KEYS
                },
            )

        private val licenseUrlHints = listOf("widevine", "playready", "clearkey", "/license", "licensekey", "acquirelicense")

        fun isLicenseRequest(url: String): Boolean {
            val lower = url.lowercase()
            return licenseUrlHints.any { lower.contains(it) }
        }

        fun onDrmLicenseObserved(
            url: String,
            headers: Map<String, String> = emptyMap(),
            pageUrl: String? = null,
            tabId: String? = null,
            documentId: String? = null,
        ) {
            if (!isLicenseRequest(url)) return
            logger.log(
                DiagCategory.SNIFFER,
                DiagLevel.INFO,
                "DRM license request detected",
                mapOf("url" to url, "headers" to headers.toString()),
            )
            val target =
                if (pageUrl != null) {
                    lastDetectedByPageAndTab[pageKey(pageUrl, tabId)]
                } else {
                    lastDetected
                }
            target?.let { found ->
                val updated = found.copy(drmLicenseUrl = url, drmLicenseRequestHeaders = headers)
                if (lastDetected?.url == found.url) lastDetected = updated
                pageUrl?.let { lastDetectedByPageAndTab[pageKey(it, tabId)] = updated }
                listeners.forEach { it.onStreamUpdated(updated) }
                observationListeners.forEach { it.onStreamObserved(updated, pageUrl, tabId, documentId) }
            }
        }

        private val seen =
            java.util.Collections.synchronizedMap(
                object : LinkedHashMap<String, Long>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_SEEN_KEYS
                },
            )

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

        private fun pageKey(
            pageUrl: String,
            tabId: String?,
        ): String =
            buildString {
                append(tabId?.length ?: -1).append(':').append(tabId.orEmpty())
                append(pageUrl)
            }

        fun registerListener(listener: StreamListener) {
            listeners += listener
        }

        fun unregisterListener(listener: StreamListener) {
            listeners -= listener
        }

        fun registerObservationListener(listener: StreamObservationListener) {
            observationListeners += listener
        }

        fun unregisterObservationListener(listener: StreamObservationListener) {
            observationListeners -= listener
        }

        fun onRequestObserved(
            url: String,
            headers: Map<String, String> = emptyMap(),
            contentType: String? = null,
            source: StreamSource = StreamSource.NETWORK,
            pageUrl: String? = null,
            tabId: String? = null,
            documentId: String? = null,
        ): StreamUrl? {
            if (StreamFormatDetector.isStream(url, contentType)) {
                return detectStream(url, headers, contentType, source, pageUrl, tabId, documentId)
            }
            if (isLicenseRequest(url)) onDrmLicenseObserved(url, headers, pageUrl, tabId, documentId)
            return null
        }

        private fun detectStream(
            url: String,
            headers: Map<String, String>,
            contentType: String?,
            source: StreamSource,
            pageUrl: String?,
            tabId: String?,
            documentId: String?,
        ): StreamUrl? {
            val detected =
                StreamUrl(
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
            pageUrl?.let { lastDetectedByPageAndTab[pageKey(it, tabId)] = detected }
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
            observationListeners.forEach { it.onStreamObserved(detected, pageUrl, tabId, documentId) }
            return if (isDuplicate) null else detected
        }
    }

/** Browser-module metadata channel; the frozen common StreamListener stays unchanged. */
fun interface StreamObservationListener {
    fun onStreamObserved(
        stream: StreamUrl,
        pageUrl: String?,
        tabId: String?,
        documentId: String?,
    )
}
