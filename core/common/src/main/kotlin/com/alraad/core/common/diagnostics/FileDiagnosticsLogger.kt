package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persistent [DiagnosticsLogger]: raw, unredacted, file-backed.
 *
 * Entries are appended verbatim (full URLs, headers, stack traces) to [logFile]
 * synchronously (single-line appends, sub-millisecond) and mirrored to an
 * in-memory flow for the Diagnostics screen, so the record always survives
 * process death and export/clear are race-free. Nothing is redacted by design.
 *
 * Line format: `timestamp<TAB>CATEGORY<TAB>LEVEL<TAB>message<TAB>key=value;...`
 * Tabs/newlines inside values are flattened to spaces so one entry is one line.
 */
class FileDiagnosticsLogger(
    private val logFile: File,
    private val scope: CoroutineScope,
    private val maxInMemory: Int = 2000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiagnosticsLogger {
    private val entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    private val fileLock = Any()
    private var nextId = 1L

    init {
        scope.launch(ioDispatcher) {
            synchronized(fileLock) {
                if (logFile.exists()) {
                    entries.value = logFile.readLines().mapNotNull { parseLine(it) }.takeLast(maxInMemory)
                }
            }
        }
    }

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
        entries.update { (it + entry).takeLast(maxInMemory) }
        synchronized(fileLock) {
            logFile.parentFile?.mkdirs()
            logFile.appendText(formatLine(entry) + "\n")
        }
    }

    override fun observeLogs(): Flow<List<DiagEntry>> = entries

    override suspend fun exportAsText(): String =
        synchronized(fileLock) {
            if (logFile.exists()) logFile.readText() else ""
        }

    override suspend fun clear() {
        entries.value = emptyList()
        synchronized(fileLock) {
            if (logFile.exists()) logFile.delete()
        }
    }

    private fun formatLine(entry: DiagEntry): String {
        val details = entry.details.entries.joinToString(";") { (k, v) -> "${k.clean()}=${v.clean()}" }
        return "${entry.timestamp}\t${entry.category}\t${entry.level}\t${entry.message.clean()}\t$details"
    }

    private fun parseLine(line: String): DiagEntry? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val details =
            parts.getOrNull(4).orEmpty()
                .split(';')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        return DiagEntry(
            id = nextId++,
            timestamp = parts[0].toLongOrNull() ?: 0L,
            category = runCatching { DiagCategory.valueOf(parts[1]) }.getOrDefault(DiagCategory.APP),
            level = runCatching { DiagLevel.valueOf(parts[2]) }.getOrDefault(DiagLevel.INFO),
            message = parts[3],
            details = details,
        )
    }

    private fun String.clean(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
