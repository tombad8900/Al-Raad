package com.alraad.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation.ActivityResult
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.getIntents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.app.di.DiagnosticsLoggerTestEntryPoint
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.diagnostics.DiagnosticsRedactor
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** End-to-end Settings > Diagnostics button wiring against the real system clipboard and chooser. */
class DiagnosticsActionsNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val logger: DiagnosticsLogger
        get() =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                DiagnosticsLoggerTestEntryPoint::class.java,
            ).diagnosticsLogger()

    @Test
    fun emptyDiagnosticsActionsShowVisibleFeedback() {
        runBlocking { logger.clear() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("الإعدادات").performClick()
        composeRule.onNodeWithContentDescription("سجل التشخيص / Diagnostics log").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { composeRule.onNodeWithText("لا توجد سجلات مطابقة").assertIsDisplayed() }.isSuccess
        }

        composeRule.onNodeWithText("نسخ / Copy").performClick()
        assertToast("لا يوجد ما يُنسخ / Nothing to copy")

        composeRule.onNodeWithText("تصدير النتائج / Export").performClick()
        assertToast("لا يوجد ما يُصدَّر / Nothing to export")
    }

    @Test
    fun settingsDiagnosticsCopyUsesSystemClipboardWithExactRedactedVisibleText() {
        val expected = openDiagnosticsWithMarker("copy")

        composeRule.onNodeWithText("نسخ / Copy").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            readClipboard()?.isNotEmpty() == true
        }

        val copied = checkNotNull(readClipboard())
        assertEquals(expected, copied)
        assertTrue(copied.contains("ui-copy-"))
        assertTrue(copied.contains("[REDACTED]"))
        assertFalse(copied.contains("diagnostics-private-token"))
    }

    @Test
    fun settingsDiagnosticsExportOpensChooserWithReadableGrantedUri() {
        val expected = openDiagnosticsWithMarker("export")
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CHOOSER))
                .respondWith(ActivityResult(android.app.Activity.RESULT_OK, Intent()))

            composeRule.onNodeWithText("تصدير النتائج / Export").performClick()
            intended(hasAction(Intent.ACTION_CHOOSER), times(1))

            val chooser = getIntents().first { it.action == Intent.ACTION_CHOOSER }

            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(send)
            assertEquals(Intent.ACTION_SEND, send!!.action)
            assertEquals("text/plain", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri: Uri = checkNotNull(send.clipData?.getItemAt(0)?.uri)
            val exported =
                context.contentResolver.openInputStream(uri)!!.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            assertEquals(expected, exported)
            assertTrue(exported.contains("[REDACTED]"))
            assertFalse(exported.contains("diagnostics-private-token"))
        } finally {
            Intents.release()
        }
    }

    private fun assertToast(message: String) {
        // Toasts are drawn by the system, outside the Activity's view tree, so look at every accessibility window.
        val automation = instrumentation.uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        var seen = ""
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                val texts = automation.windows.flatMap { window -> window.root?.let(::collectTexts).orEmpty() }
                seen = "windows=${automation.windows.size} texts=${texts.take(30)}"
                texts.any { it == message }
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Toast \"$message\" not found in any window; $seen", e)
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(node.text?.toString()) +
            (0 until node.childCount).flatMap {
                    index ->
                node.getChild(index)?.let(::collectTexts).orEmpty()
            }

    private fun capture(name: String) {
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "scenario-$name")
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openDiagnosticsWithMarker(action: String): String {
        val marker = "ui-$action-${System.currentTimeMillis()}"
        logger.log(
            DiagCategory.APP,
            DiagLevel.WARN,
            marker,
            mapOf("url" to "https://example.test/?token=diagnostics-private-token", "note" to "second-detail-line"),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("الإعدادات").performClick()
        composeRule.onNodeWithContentDescription("سجل التشخيص / Diagnostics log").performClick()
        try {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                runCatching { composeRule.onNodeWithText(marker, substring = true).assertIsDisplayed() }.isSuccess
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val logged = runBlocking { logger.observeLogs().first() }.any { it.message == marker }
            capture("diag-marker-timeout-$action.png")
            val tree = composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = 8).take(1_800)
            throw AssertionError("Marker $marker not shown; loggerHasMarker=$logged tree=$tree", e)
        }
        capture("diag-$action.png")
        val entries = runBlocking { logger.observeLogs().first() }.map(DiagnosticsRedactor::redact)
        val expected =
            entries.joinToString("\n") { entry ->
                val details = entry.details.entries.joinToString(";") { (key, value) -> "$key=$value" }
                "${entry.timestamp}\t${entry.category}\t${entry.level}\t${entry.message}\t$details"
            }
        assertTrue(expected.contains(marker))
        assertTrue(expected.contains("[REDACTED]"))
        return expected
    }

    private fun readClipboard(): String? {
        var value: String? = null
        instrumentation.runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            value = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return value
    }
}
