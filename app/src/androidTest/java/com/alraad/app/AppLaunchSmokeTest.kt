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
import java.io.File
import java.io.FileOutputStream
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
    }
}
