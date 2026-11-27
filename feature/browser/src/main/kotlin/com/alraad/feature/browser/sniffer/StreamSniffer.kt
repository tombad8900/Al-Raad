package com.alraad.feature.browser.sniffer

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.StreamListener
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects media streams from observed requests (WebView network, JS bridge, DOM).
 * Captures the full request headers verbatim (needed for later playback), the
 * Content-Length size when present, and dedups repeated observations of the same
 * URL+headers pair. Every detection is logged raw and unredacted.
 */
@Singleton
class StreamSniffer
    @Inject
    constructor(
        private val logger: DiagnosticsLogger,
    ) {
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<StreamListener>()
        private val seen = ConcurrentHashMap<String, Long>()

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
        ): StreamUrl? {
            if (!StreamFormatDetector.isStream(url, contentType)) return null
            val key = url + "|" + headers.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
            val isDuplicate = seen.put(key, System.currentTimeMillis()) != null
            val stream =
                if (isDuplicate) {
                    null
                } else {
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
                }
            stream?.let { detected ->
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
            return stream
        }
    }
