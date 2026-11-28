package com.alraad.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.app.navigation.CopyResult
import com.alraad.app.navigation.ExportResult
import com.alraad.app.navigation.copyDiagnostics
import com.alraad.app.navigation.exportDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real clipboard and FileProvider handoff for the diagnostics Copy and Export actions (emulator). */
class DiagnosticsShareTest {
    // Android only lets the focused app read the clipboard, so the clipboard tests run with an Activity in front.
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val log = "2026-11-28\tWEBVIEW\tWARN\tLoad failed\turl=https://example.test/a.ico"

    private fun awaitFocus() {
        val end = System.currentTimeMillis() + 10_000
        var focused = false
        while (!focused && System.currentTimeMillis() < end) {
            activityRule.scenario.onActivity { focused = it.hasWindowFocus() }
            if (!focused) Thread.sleep(200)
        }
        assertTrue("MainActivity never gained window focus", focused)
    }

    @Test fun copyPutsTheLogOnTheSystemClipboard() {
        awaitFocus()
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
        awaitFocus()
        var copied = CopyResult.COPIED
        var clipboardText: String? = null
        instrumentation.runOnMainSync {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("sentinel", "keep-existing-clip"))
            copied = copyDiagnostics(context, "  ")
            clipboardText = manager.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(CopyResult.EMPTY, copied)
        assertEquals("keep-existing-clip", clipboardText)
    }

    @Test fun clipboardFailureIsReportedWithoutThrowing() {
        val brokenContext =
            object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? =
                    if (name == Context.CLIPBOARD_SERVICE) Any() else super.getSystemService(name)
            }
        assertEquals(CopyResult.FAILED, copyDiagnostics(brokenContext, log))
        assertEquals(
            ExportResult.FAILED,
            exportDiagnostics(brokenContext, log) { error("share launch failed") },
        )
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
