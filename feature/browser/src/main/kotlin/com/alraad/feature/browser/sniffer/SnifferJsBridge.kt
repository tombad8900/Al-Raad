package com.alraad.feature.browser.sniffer

import android.webkit.JavascriptInterface
import com.alraad.core.common.contracts.StreamSource
import javax.inject.Inject
import org.json.JSONObject

/**
 * JS bridge: page-side script posts JSON {url, contentType?, headers?{k:v}} and it is
 * fed to the StreamSniffer with source JS_BRIDGE, preserving headers verbatim.
 */
class SnifferJsBridge
    @Inject
    constructor(
        private val sniffer: StreamSniffer,
    ) {
        @JavascriptInterface
        fun onStreamFound(payload: String) {
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
            val url = json.optString("url").takeIf { it.isNotBlank() } ?: return
            val contentType = json.optString("contentType").takeIf { it.isNotBlank() }
            val headers = mutableMapOf<String, String>()
            json.optJSONObject("headers")?.let { obj ->
                for (key in obj.keys()) headers[key] = obj.optString(key)
            }
            sniffer.onRequestObserved(url, headers, contentType, StreamSource.JS_BRIDGE)
        }
    }
