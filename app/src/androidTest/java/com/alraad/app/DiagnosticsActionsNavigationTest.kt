package com.alraad.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityResult
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.getIntents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.diagnostics.DiagnosticsRedactor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.not
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
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DiagnosticsLoggerEntryPoint::class.java,
        ).diagnosticsLogger()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DiagnosticsLoggerEntryPoint {
        fun diagnosticsLogger(): DiagnosticsLogger
    }

    @Test
    fun emptyDiagnosticsActionsShowVisibleFeedback() {
        runBlocking { logger.clear() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("الإعدادات").performClick()
        composeRule.onNodeWithContentDescription("سجل التشخيص / Diagnostics log").performClick()
        composeRule.onNodeWithText("لا توجد سجلات مطابقة").assertIsDisplayed()

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
            val exported = context.contentResolver.openInputStream(uri)!!.use {
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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                onView(withText(message))
                    .inRoot(withDecorView(not(composeRule.activity.window.decorView)))
                    .check(matches(isDisplayed()))
            }.isSuccess
        }
    }

    private fun openDiagnosticsWithMarker(action: String): String {
        val marker = "ui-$action-${System.currentTimeMillis()}"
        logger.log(
            DiagCategory.APP,
            DiagLevel.WARN,
            marker,
            mapOf("url" to "https://example.test/?token=diagnostics-private-token"),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("الإعدادات").performClick()
        composeRule.onNodeWithContentDescription("سجل التشخيص / Diagnostics log").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { composeRule.onNodeWithText(marker, substring = true).assertIsDisplayed() }.isSuccess
        }
        val entries = runBlocking { logger.observeLogs().first() }.map(DiagnosticsRedactor::redact)
        val expected = entries.joinToString("\n") { entry ->
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
