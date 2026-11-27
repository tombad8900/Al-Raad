package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.diagnostics.FileDiagnosticsLogger
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Runtime smoke: launches the real app on an emulator, verifies the Compose tree
 * renders, and writes a full-screen PNG screenshot for CI artifact upload. The
 * screenshot goes to the public Downloads collection via MediaStore (API 29+):
 * the prior CI run showed the app package no longer resolvable via run-as at
 * pull time, so the public collection (readable by adb regardless of package
 * state) is the reliable export path; on older APIs it falls back to the app's
 * external files dir.
 */
class AppLaunchSmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun appLaunchesAndRenders() {
        composeRule.waitForIdle()
        composeRule.onRoot(useUnmergedTree = true).assertIsDisplayed()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertTrue("screenshot capture failed", screenshot != null && screenshot.width > 0)
        val path =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "app-launch.png")
                        put(MediaStore.Downloads.MIME_TYPE, "image/png")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                "${Environment.DIRECTORY_DOWNLOADS}/app-launch.png (MediaStore)"
            } else {
                val out = File(context.getExternalFilesDir(null), "app-launch.png")
                FileOutputStream(out).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out.absolutePath
            }
        println("RUNTIME-SCREENSHOT: $path")

        // Exercise the raw diagnostics export path on-device: log one marker through the
        // real FileDiagnosticsLogger, export, and publish the text next to the screenshot
        // so CI can upload it for inspection.
        // Isolated logger with its own file: exercises the real FileDiagnosticsLogger
        // export path on-device without publishing the app's live log (which may
        // contain browsing URLs, headers, or other session data).
        val logger =
            FileDiagnosticsLogger(
                File(context.cacheDir, "smoke-diagnostics.log"),
                CoroutineScope(Dispatchers.IO),
            )
        val marker = "smoke-export-marker"
        logger.log(DiagCategory.APP, DiagLevel.INFO, marker, mapOf("source" to "AppLaunchSmokeTest"))
        val export = runBlocking { logger.exportAsText() }
        assertTrue("diagnostics export missing marker entry", export.contains(marker))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "diagnostics-export.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { it.write(export.toByteArray()) }
        } else {
            File(context.getExternalFilesDir(null), "diagnostics-export.txt").writeText(export)
        }
        println("RUNTIME-DIAGNOSTICS: exported ${export.length} chars")
    }
}
