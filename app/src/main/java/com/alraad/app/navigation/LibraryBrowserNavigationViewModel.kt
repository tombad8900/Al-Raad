package com.alraad.app.navigation

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.alraad.feature.browser.tabs.TabManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * App-layer navigation bridge for reopening a saved stream's original page.
 * Only the source page URL is opened; stream/CDN headers are deliberately not used.
 */
@HiltViewModel
class LibraryBrowserNavigationViewModel
    @Inject
    constructor(
        private val tabManager: TabManager,
    ) : ViewModel() {

        /**
         * Opens a valid web source in a fresh browser tab, then requests navigation
         * to the in-app browser. Returns false without side effects for missing or
         * non-web URLs.
         */
        fun openSourcePage(
            sourcePageUrl: String?,
            onNavigateToBrowser: () -> Unit,
        ): Boolean {
            val url = sourcePageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val uri = Uri.parse(url)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                return false
            }

            tabManager.openTab(url)
            onNavigateToBrowser()
            return true
        }
    }
