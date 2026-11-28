package com.alraad.feature.browser.tabs

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.alraad.feature.browser.ui.BrowserWebViewPool
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "",
)

data class TabState(
    val tabs: List<BrowserTab> = emptyList(),
    val activeTabId: String? = null,
)

@Singleton
class TabManager
    @Inject
    constructor() {
        private val _state = MutableStateFlow(TabState())

        @Volatile
        private var retainedWebViewPool: BrowserWebViewPool? = null

        /** Process-scoped pool: survives BrowserScreen disposal while section navigation occurs. */
        fun webViewPool(context: Context): BrowserWebViewPool =
            retainedWebViewPool ?: synchronized(this) {
                retainedWebViewPool ?: BrowserWebViewPool(context.applicationContext).also {
                    retainedWebViewPool = it
                }
            }

        val state: StateFlow<TabState> = _state.asStateFlow()

        /** androidTest isolation: clears all tabs between instrumented tests. */
        @VisibleForTesting
        fun reset() {
            retainedWebViewPool?.clear()
            _state.value = TabState()
        }

        fun openTab(url: String = "about:blank"): BrowserTab {
            val tab = BrowserTab(id = UUID.randomUUID().toString(), url = url)
            _state.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
            return tab
        }

        fun closeTab(id: String) {
            retainedWebViewPool?.release(id)
            _state.update { state ->
                val remaining = state.tabs.filterNot { it.id == id }
                val newActive =
                    if (state.activeTabId == id) remaining.lastOrNull()?.id else state.activeTabId
                state.copy(tabs = remaining, activeTabId = newActive)
            }
        }

        fun switchTo(id: String) {
            _state.update { state ->
                if (state.tabs.any { it.id == id }) state.copy(activeTabId = id) else state
            }
        }

        fun updateTab(
            id: String,
            url: String? = null,
            title: String? = null,
        ) {
            _state.update { state ->
                state.copy(
                    tabs =
                        state.tabs.map {
                            if (it.id == id) it.copy(url = url ?: it.url, title = title ?: it.title) else it
                        },
                )
            }
        }
    }
