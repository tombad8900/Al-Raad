package com.alraad.app.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val CLIP_LABEL = "diagnostics log"
private const val TAG = "DiagnosticsShare"

/** Copies the already redacted visible log text. Returns false when there is nothing to copy. */
internal fun copyDiagnostics(
    context: Context,
    text: String,
): CopyResult {
    if (text.isBlank()) return CopyResult.EMPTY
    return runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }.fold(
        onSuccess = { CopyResult.COPIED },
        onFailure = {
            Log.w(TAG, "clipboard copy failed: " + it.javaClass.simpleName)
            CopyResult.FAILED
        },
    )
}

/**
 * Writes the redacted visible log to cache and hands it to the share chooser through [launch].
 * A failure never includes log content in its own log line; the text is copied instead.
 */
internal fun exportDiagnostics(
    context: Context,
    text: String,
    launch: (Intent) -> Unit,
): ExportResult {
    if (text.isBlank()) return ExportResult.EMPTY
    val shared =
        runCatching {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "alraad-diagnostics-" + System.currentTimeMillis() + ".txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(CLIP_LABEL, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            launch(Intent.createChooser(send, null))
        }.onFailure { Log.w(TAG, "export failed: " + it.javaClass.simpleName) }
    return when {
        shared.isSuccess -> ExportResult.SHARED
        copyDiagnostics(context, text) == CopyResult.COPIED -> ExportResult.COPIED_INSTEAD
        else -> ExportResult.FAILED
    }
}
