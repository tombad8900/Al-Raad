package com.alraad.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.app.navigation.CopyResult
import com.alraad.app.navigation.ExportResult
import com.alraad.app.navigation.copyDiagnostics
import com.alraad.app.navigation.exportDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real clipboard and FileProvider handoff for the diagnostics Copy and Export actions (emulator). */
class DiagnosticsShareTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val log = "2026-11-28\tWEBVIEW\tWARN\tLoad failed\turl=https://example.test/a.ico"

    @Test fun copyPutsTheLogOnTheSystemClipboard() {
        var copied = CopyResult.FAILED
        var clip: String? = null
        instrumentation.runOnMainSync {
            copied = copyDiagnostics(context, log)
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip = manager.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(CopyResult.COPIED, copied)
        assertEquals(log, clip)
    }

    @Test fun copyOfAnEmptyLogReportsNothingAndLeavesClipboardAlone() {
        var copied = CopyResult.COPIED
        instrumentation.runOnMainSync { copied = copyDiagnostics(context, "  ") }
        assertEquals(CopyResult.EMPTY, copied)
    }

    @Test fun exportHandsAReadableTextFileToTheChooser() {
        var launched: Intent? = null
        val result = exportDiagnostics(context, log) { launched = it }
        assertEquals(ExportResult.SHARED, result)
        val chooser = launched
        assertNotNull(chooser)
        @Suppress("DEPRECATION")
        val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = send.clipData?.getItemAt(0)?.uri
        assertNotNull(uri)
        val text = context.contentResolver.openInputStream(uri!!)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(log, text)
    }

    @Test fun exportOfAnEmptyLogSharesNothing() {
        var launched = false
        val result = exportDiagnostics(context, "") { launched = true }
        assertEquals(ExportResult.EMPTY, result)
        assertFalse(launched)
    }

    @Test fun exportThatCannotLaunchCopiesTheLogInstead() {
        var result: ExportResult? = null
        instrumentation.runOnMainSync {
            result = exportDiagnostics(context, log) { error("no share target") }
        }
        assertEquals(ExportResult.COPIED_INSTEAD, result)
    }
}
