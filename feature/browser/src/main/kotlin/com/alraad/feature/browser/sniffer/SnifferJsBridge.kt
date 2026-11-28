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

    fun clearPage() = pageContext.set(null)

    @Suppress("ReturnCount")
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
                val value = source.optString(rawName)
                if (
                    name in SAFE_HEADERS && rawName.length <= MAX_HEADER_NAME_LENGTH &&
                    value.isNotBlank() && value.length <= MAX_HEADER_VALUE_LENGTH &&
                    value.none { it.code < 0x20 || it.code == 0x7f }
                ) {
                    put(HEADER_NAMES.getValue(name), value)
                    count++
                }
            }
        }

    private fun isHttpUrl(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_URL_LENGTH &&
            runCatching { URI(value) }.getOrNull()?.let { uri ->
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null
            } == true

    private data class PageContext(val pageUrl: String, val tabId: String, val token: String)

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
