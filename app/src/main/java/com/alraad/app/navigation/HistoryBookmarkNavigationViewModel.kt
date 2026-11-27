package com.alraad.app.navigation

import androidx.lifecycle.ViewModel
import com.alraad.feature.browser.tabs.TabManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import javax.inject.Inject

/** Opens a History/Bookmark URL in a fresh in-app browser tab before navigation. */
@HiltViewModel
class HistoryBookmarkNavigationViewModel
    @Inject
    constructor(
        private val tabManager: TabManager,
    ) : ViewModel() {
        fun open(
            url: String,
            onNavigateToBrowser: () -> Unit,
        ): Boolean {
            val target = url.trim()
            val uri = runCatching { URI(target) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return false

            tabManager.openTab(target)
            onNavigateToBrowser()
            return true
        }
    }
