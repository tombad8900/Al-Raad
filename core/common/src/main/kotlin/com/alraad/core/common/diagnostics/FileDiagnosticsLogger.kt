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
 * Persistent [DiagnosticsLogger]: file-backed with credentials redacted before
 * persistence and again when legacy entries are loaded for display or export.
 *
 * Safe URL context, non-sensitive headers, and stack traces remain available.
 * The same sanitized entries feed the in-memory flow and text export.
 *
 * Line format v2: `V2<TAB>timestamp<TAB>CATEGORY<TAB>LEVEL<TAB>enc(message)<TAB>enc(key)=enc(value);...`
 * where enc() is UTF-8 percent-encoding, so details containing ';', '=', tabs or
 * newlines round-trip losslessly. Legacy v1 lines (no V2 marker, raw values) stay
 * readable. Export decodes entries while preserving only sanitized diagnostic content.
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
                    val rawLines = logFile.readLines()
                    val loaded = rawLines.mapNotNull { parseLine(it) }
                    entries.value = loaded.takeLast(maxInMemory)
                    // Migrate valid legacy/raw entries in place so secrets are not left
                    // on disk after the new redaction policy is introduced.
                    if (loaded.size == rawLines.count { it.isNotBlank() }) {
                        val safeContent = loaded.joinToString(separator = "\n", postfix = if (loaded.isEmpty()) "" else "\n", transform = ::formatLine)
                        if (safeContent != logFile.readText()) logFile.writeText(safeContent)
                    }
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
            DiagnosticsRedactor.redact(
                DiagEntry(
                    id = nextId++,
                    timestamp = System.currentTimeMillis(),
                    category = category,
                    level = level,
                    message = message,
                    details = details,
                ),
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
            if (!logFile.exists()) return ""
            // Decoded rendering retains safe diagnostics and excludes credential values.
            logFile.readLines().mapNotNull { parseLine(it) }
                .joinToString("\n") { entry ->
                    val details = entry.details.entries.joinToString(";") { (k, v) -> "$k=$v" }
                    "${entry.timestamp}\t${entry.category}\t${entry.level}\t${entry.message}\t$details"
                }
        }

    override suspend fun clear() {
        entries.value = emptyList()
        synchronized(fileLock) {
            if (logFile.exists()) logFile.delete()
        }
    }

    private fun formatLine(entry: DiagEntry): String {
        val details = entry.details.entries.joinToString(";") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "V2\t${entry.timestamp}\t${entry.category}\t${entry.level}\t${enc(entry.message)}\t$details"
    }

    private fun parseLine(line: String): DiagEntry? {
        val parts = line.split('\t')
        val isV2 = parts.firstOrNull() == "V2"
        val offset = if (isV2) 1 else 0
        if (parts.size < offset + 4) return null
        // Legacy v1 lines (no marker) carry raw, lossy values; V2 values are
        // percent-encoded and decode losslessly.
        val detailsRaw = parts.getOrNull(offset + 4).orEmpty()
        val details =
            detailsRaw
                .split(';')
                .filter { it.contains('=') }
                .associate {
                    val k = it.substringBefore('=')
                    val v = it.substringAfter('=')
                    if (isV2) dec(k) to dec(v) else k to v
                }
        return DiagnosticsRedactor.redact(
            DiagEntry(
                id = nextId++,
                timestamp = parts[offset].toLongOrNull() ?: 0L,
                category = runCatching { DiagCategory.valueOf(parts[offset + 1]) }.getOrDefault(DiagCategory.APP),
                level = runCatching { DiagLevel.valueOf(parts[offset + 2]) }.getOrDefault(DiagLevel.INFO),
                message = if (isV2) dec(parts[offset + 3]) else parts[offset + 3],
                details = details,
            ),
        )
    }

    /**
     * Strict percent codec: every byte outside [A-Za-z0-9._~-] becomes %XX (UTF-8),
     * so ';', '=', tabs, newlines and non-ASCII survive losslessly. Unlike
     * URLEncoder, '+' and space are distinct.
     */
    private fun enc(value: String): String {
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch in "._~-") {
                out.append(ch)
            } else {
                out.append('%').append("%02X".format(c))
            }
        }
        return out.toString()
    }

    private fun dec(value: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 2 < value.length + 1 && i + 2 <= value.length - 1 + 1) {
                val hex = value.substring(i + 1, minOf(i + 3, value.length))
                val parsed = hex.toIntOrNull(16)
                if (parsed != null && hex.length == 2) {
                    bytes.write(parsed)
                    i += 3
                    continue
                }
            }
            bytes.write(value[i].code)
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
