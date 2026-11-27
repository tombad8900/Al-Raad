package com.alraad.core.common.fakes

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [DiagnosticsLogger] for tests and development.
 * Stores entries raw and unredacted, exactly as received.
 */
class FakeDiagnosticsLogger : DiagnosticsLogger {
    private val entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    private var nextId = 1L

    override fun log(
        category: DiagCategory,
        level: DiagLevel,
        message: String,
        details: Map<String, String>,
    ) {
        val entry =
            DiagEntry(
                id = nextId++,
                timestamp = System.currentTimeMillis(),
                category = category,
                level = level,
                message = message,
                details = details,
            )
        entries.update { it + entry }
    }

    override fun observeLogs(): Flow<List<DiagEntry>> = entries

    override suspend fun exportAsText(): String =
        entries.value.joinToString(separator = "\n") { entry ->
            buildString {
                append(entry.timestamp)
                append(" [").append(entry.category).append('/').append(entry.level).append("] ")
                append(entry.message)
                if (entry.details.isNotEmpty()) {
                    append(" | ")
                    append(entry.details.entries.joinToString(", ") { (k, v) -> "$k=$v" })
                }
            }
        }

    override suspend fun clear() {
        entries.value = emptyList()
    }
}
