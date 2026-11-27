package com.alraad.feature.browser.web

import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger

/**
 * Rejects window.open() popup creation (gesture-triggered or not) by refusing to
 * create the child WebView. The popup request is logged raw for inspection.
 */
class PopupBlockingWebChromeClient(
    private val logger: DiagnosticsLogger,
) : WebChromeClient() {
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        logger.log(
            DiagCategory.WEBVIEW,
            DiagLevel.INFO,
            "Popup blocked",
            mapOf("url" to (view.url ?: ""), "isDialog" to isDialog.toString(), "isUserGesture" to isUserGesture.toString()),
        )
        return false
    }
}
