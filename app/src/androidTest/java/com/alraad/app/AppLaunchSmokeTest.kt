package com.alraad.app

import android.graphics.Bitmap
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
 * renders, and writes a full-screen PNG screenshot to the app's external files dir
 * for CI artifact upload (screenshot path printed in the test log).
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
        val outDir = context.getExternalFilesDir(null)!!
        val out = File(outDir, "app-launch.png")
        FileOutputStream(out).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RUNTIME-SCREENSHOT: ${out.absolutePath}")
        assertTrue("screenshot not written", out.length() > 0)
    }
}
