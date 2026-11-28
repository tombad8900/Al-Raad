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
    private val early = ArrayDeque<String>()

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
        val pending = synchronized(early) { early.toList().also { early.clear() } }
        pending.forEach(::onRequestObserved)
        return token
    }

    fun clearPage() = pageContext.set(null)

    @Suppress("ReturnCount")
    @JavascriptInterface
    fun onRequestObserved(payload: String) {
        if (payload.isEmpty() || payload.length > MAX_PAYLOAD_LENGTH) return
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val context = pageContext.get()
        if (context == null || json.optString("pageUrl") != context.pageUrl) {
            // A document-start script can report before onPageStarted binds its page: hold a few for that page only.
            if (context == null && isHttpUrl(json.optString("pageUrl"))) {
                synchronized(early) {
                    if (early.size >= MAX_EARLY) early.removeFirst()
                    early.addLast(payload)
                }
            }
            return
        }
        val token = json.optString("token")
        if (token.isNotEmpty() && token != context.token) return
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

    @Suppress("ComplexCondition")
    private fun safeHeaders(source: JSONObject): Map<String, String> =
        buildMap {
            val keys = source.keys()
            var count = 0
            while (keys.hasNext() && count < MAX_HEADERS) {
                val rawName = keys.next()
                val name = rawName.lowercase()
                val value = source.optString(rawName)
                if (
                    name in SAFE_HEADERS && value.isNotBlank() && value.length <= MAX_HEADER_VALUE_LENGTH &&
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
        const val MAX_EARLY = 32
        const val MAX_URL_LENGTH = 8_192
        const val MAX_HEADERS = 12
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
