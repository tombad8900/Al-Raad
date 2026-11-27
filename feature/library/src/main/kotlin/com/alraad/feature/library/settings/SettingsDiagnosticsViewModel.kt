package com.alraad.feature.library.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings + Diagnostics state (Issue #9, part C). */
@HiltViewModel
class SettingsDiagnosticsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val diagnosticsLogger: DiagnosticsLogger,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings> =
            settingsRepository.observeSettings()
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

        val categoryFilter = MutableStateFlow<DiagCategory?>(null)

        val logs: StateFlow<List<DiagEntry>> =
            combine(diagnosticsLogger.observeLogs(), categoryFilter) { entries, filter ->
                if (filter == null) entries else entries.filter { it.category == filter }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        fun update(transform: (AppSettings) -> AppSettings) {
            viewModelScope.launch { settingsRepository.update(transform(settings.value)) }
        }

        suspend fun exportLogs(): String = diagnosticsLogger.exportAsText()

        fun clearLogs() {
            viewModelScope.launch { diagnosticsLogger.clear() }
        }
    }
