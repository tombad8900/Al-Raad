package com.alraad.core.common.contracts

import kotlinx.coroutines.flow.Flow

/**
 * Frozen diagnostics contracts (Issue #1). Diagnostic payloads retain useful
 * URLs and non-sensitive headers; credentials are redacted before persistence
 * and before any display or export.
 */

enum class DiagCategory { WEBVIEW, ADBLOCK, SNIFFER, PLAYER, LIBRARY, DATABASE, APP, CRASH }

enum class DiagLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

data class DiagEntry(
    val id: Long = 0,
    val timestamp: Long,
    val category: DiagCategory,
    val level: DiagLevel,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

interface DiagnosticsLogger {
    fun log(
        category: DiagCategory,
        level: DiagLevel,
        message: String,
        details: Map<String, String> = emptyMap(),
    )

    fun observeLogs(): Flow<List<DiagEntry>>

    suspend fun exportAsText(): String

    suspend fun clear()
}
