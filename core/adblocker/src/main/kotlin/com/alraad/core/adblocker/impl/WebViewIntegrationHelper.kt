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

        fun webViewClient(
            onPageFinished: (WebView) -> Unit = {},
            onRequestObserved: (url: String, headers: Map<String, String>, pageUrl: String?) -> Unit = { _, _, _ -> },
            onPageStart: (WebView, String) -> Unit = { _, _ -> },
            onNavigationRequested: (WebView, String) -> Unit = { _, _ -> },
            onPageDocumentStarted: (WebView, String) -> String? = { _, _ -> null },
            onPageUrlChanged: (WebView, String?, String?, String, Boolean) -> String? =
                { _, _, _, _, _ -> null },
            onRequestObservedWithContext: (
                url: String,
                headers: Map<String, String>,
                pageUrl: String?,
                documentId: String?,
            ) -> Unit = { _, _, _, _ -> },
        ): WebViewClient =
            object : WebViewClient() {
                // Cached from UI-thread navigation callbacks: shouldInterceptRequest runs
                // off the WebView UI thread, so reading view.url here would touch WebView
                // state on the wrong thread.
                @Volatile private var currentPageContext = CachedPageContext(null, null)

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?,
                ) {
                    val documentId = onPageDocumentStarted(view, url)
                    currentPageContext = CachedPageContext(url, documentId)
                    onPageStart(view, url)
                    super.onPageStarted(view, url, favicon)
                }

                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String,
                    isReload: Boolean,
                ) {
                    val previousContext = currentPageContext
                    val previousUrl = previousContext.url
                    val documentId = onPageUrlChanged(view, previousUrl, previousContext.documentId, url, isReload)
                    currentPageContext = CachedPageContext(url, documentId)
                    super.doUpdateVisitedHistory(view, url, isReload)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean {
                    val scheme = request.url.scheme
                    if (request.isForMainFrame && !request.isRedirect) {
                        if (scheme == "http" || scheme == "https") {
                            onNavigationRequested(view, request.url.toString())
                        }
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (request.isForMainFrame) {
                        // A new main-frame request must not inherit the previous document token.
                        // BrowserWebView can resolve the exact pending navigation by this URL.
                        currentPageContext = CachedPageContext(url, null)
                    }
                    // Snapshot the owning document before any asynchronous work or navigation
                    // can replace the WebView's current page context.
                    val pageContext = currentPageContext
                    val headers = request.requestHeaders.orEmpty()
                    onRequestObserved(url, headers, pageContext.url)
                    onRequestObservedWithContext(url, headers, pageContext.url, pageContext.documentId)
                    val type =
                        RequestTypeMapper.from(
                            url,
                            request.isForMainFrame,
                            headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value,
                            headers.entries.firstOrNull { it.key.equals("X-Requested-With", true) }?.value,
                        )
                    val blocked = runBlocking(Dispatchers.IO) { adBlocker.shouldBlock(url, pageContext.url.orEmpty(), type) }
                    if (blocked) {
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
                    if (request.isForMainFrame) {
                        // Replace WebView's unreadable default error text with a dark,
                        // RTL-readable page offering a retry back to the failed URL.
                        val failedUrl = jsQuote(request.url.toString())
                        val description = error.description?.toString() ?: ""
                        view.loadDataWithBaseURL(
                            null,
                            "<html dir=\"rtl\"><head><meta name=\"viewport\" content=\"width=device-width\">" +
                                "<body style=\"background:#0B0E13;color:#E5E9F0;font-family:sans-serif;" +
                                "display:flex;flex-direction:column;align-items:center;justify-content:center;" +
                                "min-height:90vh;margin:0;text-align:center\">" +
                                "<h2>تعذر تحميل الصفحة</h2><p>" + description + "</p>" +
                                "<button style=\"padding:12px 24px;font-size:16px\" " +
                                "onclick=\"location.href=" + failedUrl + "\">إعادة المحاولة</button>" +
                                "</body></html>",
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
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
                    scope.launch(Dispatchers.Main) {
                        // Global ad-block OFF returns empty CSS, so nothing is injected.
                        val css = adBlocker.cosmeticCss(url)
                        if (css.isEmpty()) return@launch
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


private data class CachedPageContext(
    val url: String?,
    val documentId: String?,
)
