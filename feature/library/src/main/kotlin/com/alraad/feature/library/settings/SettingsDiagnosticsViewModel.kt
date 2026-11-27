package com.alraad.feature.library.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import com.alraad.core.common.diagnostics.DiagnosticsRedactor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings and searchable, filterable diagnostics state. */
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
        val levelFilter = MutableStateFlow<DiagLevel?>(null)
        val searchQuery = MutableStateFlow("")

        val logs: StateFlow<List<DiagEntry>> =
            combine(diagnosticsLogger.observeLogs(), categoryFilter, levelFilter, searchQuery) {
                    entries,
                    category,
                    level,
                    query,
                ->
                val safeEntries = entries.map(DiagnosticsRedactor::redact)
                safeEntries.filter { entry ->
                    (category == null || entry.category == category) &&
                        (level == null || entry.level == level) &&
                        matchesQuery(entry, query)
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        fun update(transform: (AppSettings) -> AppSettings) {
            viewModelScope.launch { settingsRepository.update(transform(settings.value)) }
        }

        suspend fun exportLogs(): String = DiagnosticsRedactor.redactText(diagnosticsLogger.exportAsText())

        /** Copies/exports the visible filtered set, already redacted for display. */
        fun exportVisibleLogs(): String =
            logs.value.joinToString("\n") { entry ->
                val details = entry.details.entries.joinToString(";") { (key, value) -> "$key=$value" }
                "${entry.timestamp}\t${entry.category}\t${entry.level}\t${entry.message}\t$details"
            }

        fun clearLogs() {
            viewModelScope.launch { diagnosticsLogger.clear() }
        }

        private fun matchesQuery(
            entry: DiagEntry,
            rawQuery: String,
        ): Boolean {
            val query = rawQuery.trim()
            if (query.isEmpty()) return true
            return entry.message.contains(query, ignoreCase = true) ||
                entry.category.name.contains(query, ignoreCase = true) ||
                entry.level.name.contains(query, ignoreCase = true) ||
                entry.details.any { (key, value) ->
                    key.contains(query, ignoreCase = true) || value.contains(query, ignoreCase = true)
                }
        }
    }
