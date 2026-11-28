package com.alraad.feature.browser.sniffer

import android.webkit.JavascriptInterface
import com.alraad.core.common.contracts.StreamSource
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

class SnifferJsBridge(
    private val sniffer: StreamSniffer,
) {
        private val pageContext = AtomicReference<PageContext?>(null)
        fun bindPage(
            pageUrl: String,
            tabId: String,
        ): String {
            if (!isHttpUrl(pageUrl) || tabId.isBlank()) {
                clearPage()
                return ""
            }
            val token = UUID.randomUUID().toString()
            pageContext.set(PageContext(pageUrl, tabId, token))
            return token
        }
        fun isBoundTo(
            pageUrl: String,
            tabId: String,
        ): Boolean =
            pageContext.get()?.let { it.pageUrl == pageUrl && it.tabId == tabId } == true

        fun clearPage() {
            pageContext.set(null)
        }
        @JavascriptInterface
        fun onRequestObserved(payload: String) {
            if (payload.isEmpty() || payload.length > MAX_PAYLOAD_LENGTH) return
            val context = pageContext.get() ?: return
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
            if (json.optString("token") != context.token || json.optString("pageUrl") != context.pageUrl) return
            val url = json.optString("url").takeIf(::isHttpUrl) ?: return
            val headers = safeHeaders(json.optJSONObject("headers") ?: return)
            sniffer.onRequestObserved(
                url = url,
                headers = headers,
                contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value,
                source = StreamSource.JS_BRIDGE,
                pageUrl = context.pageUrl,
                tabId = context.tabId,
            )
        }
        private fun safeHeaders(source: JSONObject): Map<String, String> =
            buildMap {
                val keys = source.keys()
                var count = 0
                while (keys.hasNext() && count < MAX_HEADERS) {
                    val rawName = keys.next()
                    val name = rawName.lowercase()
                    if (name !in SAFE_HEADERS || rawName.length > MAX_HEADER_NAME_LENGTH) continue
                    val value = source.optString(rawName)
                    if (value.isBlank() || value.length > MAX_HEADER_VALUE_LENGTH) continue
                    if (value.any { it.code < 0x20 || it.code == 0x7f }) continue
                    put(HEADER_NAMES.getValue(name), value)
                    count++
                }
            }

        private fun isHttpUrl(value: String): Boolean {
            if (value.isBlank() || value.length > MAX_URL_LENGTH) return false
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }

        private data class PageContext(
            val pageUrl: String,
            val tabId: String,
            val token: String,
        )

        private companion object {
            const val MAX_PAYLOAD_LENGTH = 16_384
            const val MAX_URL_LENGTH = 8_192
            const val MAX_HEADERS = 12
            const val MAX_HEADER_NAME_LENGTH = 64
            const val MAX_HEADER_VALUE_LENGTH = 1_024
            val SAFE_HEADERS = setOf("accept", "authorization", "content-type", "range")
            val HEADER_NAMES =
                mapOf(
                    "accept" to "Accept",
                    "authorization" to "Authorization",
                    "content-type" to "Content-Type",
                    "range" to "Range",
                )
        }
    }
