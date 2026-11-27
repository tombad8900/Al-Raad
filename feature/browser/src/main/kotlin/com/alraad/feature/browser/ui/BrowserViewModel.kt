package com.alraad.feature.browser.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import com.alraad.core.common.contracts.StreamListener
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.actions.StreamActions
import com.alraad.feature.browser.tabs.TabManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class BrowserViewModel
    @Inject
    constructor(
        val tabManager: TabManager,
        val webViewIntegration: WebViewIntegrationHelper,
        val diagnosticsLogger: DiagnosticsLogger,
        val sniffer: com.alraad.feature.browser.sniffer.StreamSniffer,
        private val streamActions: StreamActions,
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings> =
            settingsRepository.observeSettings()
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

        /** Streams detected by the sniffer, newest first; updates replace by URL. */
        val detectedStreams: StateFlow<List<StreamUrl>>

        init {
            val streams = kotlinx.coroutines.flow.MutableStateFlow<List<StreamUrl>>(emptyList())
            detectedStreams = streams
            sniffer.registerListener(
                object : StreamListener {
                    override fun onStreamDetected(stream: StreamUrl) {
                        streams.value = listOf(stream) + streams.value
                    }

                    override fun onStreamUpdated(stream: StreamUrl) {
                        streams.value = streams.value.map { if (it.url == stream.url) stream else it }
                    }
                },
            )
        }

        /** Starts playback of a detected stream; the PLAYER route renders it via currentStream. */
        fun playStream(stream: StreamUrl) = streamActions.play(stream)
    }
