package com.alraad.core.adblocker.impl

import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Wires the offline AdBlocker into a WebView: network requests to blocked hosts get
 * an empty response (no network call), and generic cosmetic CSS is injected after
 * each page load. Raw blocked URLs are logged unredacted.
 */
@Singleton
class WebViewIntegrationHelper
    @Inject
    constructor(
        private val adBlocker: AdBlocker,
        private val logger: DiagnosticsLogger,
        private val scope: CoroutineScope,
    ) {
        /**
         * Full diagnostics record (AGENTS.md): every failed request is logged -
         * main-frame failures at ERROR, subresource failures at WARN.
         */
        internal fun logLoadError(
            url: String,
            errorCode: Int,
            description: String,
            isMainFrame: Boolean,
        ) {
            logger.log(
                DiagCategory.WEBVIEW,
                if (isMainFrame) DiagLevel.ERROR else DiagLevel.WARN,
                "Load failed",
                mapOf(
                    "url" to url,
                    "errorCode" to errorCode.toString(),
                    "description" to description,
                    "mainFrame" to isMainFrame.toString(),
                ),
            )
        }

        fun webViewClient(onPageFinished: (WebView) -> Unit = {}): WebViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    val blocked = runBlocking(Dispatchers.IO) { adBlocker.shouldBlock(url) }
                    if (blocked) {
                        logger.log(DiagCategory.ADBLOCK, DiagLevel.DEBUG, "Intercepted", mapOf("url" to url))
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            204,
                            "No Content",
                            emptyMap(),
                            "".byteInputStream(),
                        )
                    }
                    return null
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    logLoadError(
                        url = request.url.toString(),
                        errorCode = error.errorCode,
                        description = error.description?.toString() ?: "",
                        isMainFrame = request.isForMainFrame,
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    logger.log(
                        DiagCategory.WEBVIEW,
                        DiagLevel.WARN,
                        "HTTP error",
                        mapOf("url" to request.url.toString(), "status" to errorResponse.statusCode.toString()),
                    )
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    val css = adBlocker.cosmeticCss(url)
                    scope.launch(Dispatchers.Main) {
                        view.evaluateJavascript(
                            "(function(){var s=document.createElement('style');s.textContent=${jsQuote(
                                css,
                            )};document.head.appendChild(s);})()",
                            null,
                        )
                    }
                    onPageFinished(view)
                }
            }

        private fun jsQuote(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'").let { "'$it'" }
    }
