package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end runtime scenario on the real app: browse a page, then prove the visit
 * is recorded in history, bookmarked, and survives an activity reopen. Each step
 * exports a full-screen PNG so CI artifacts show the actual rendered UI, not only
 * semantics state. Requires emulator network access for example.com; if the page
 * cannot load, the history assertion fails honestly instead of passing on stale state.
 */
class AppScenarioTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun browseThenHistoryBookmarkAndReopen() {
        composeRule.waitForIdle()
        waitForNode(hasSetTextAction(), 20_000)

        // 1. Browse: type an address and submit via IME Go.
        composeRule.onNode(hasSetTextAction()).performTextInput("example.com")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        Thread.sleep(5_000)
        capture("scenario-02-browsing.png")

        // 2. History: the finished load must appear as an entry (proves recording).
        composeRule.onNodeWithText("السجل").performClick()
        waitForNode(hasText("example.com", substring = true), 30_000)
        capture("scenario-03-history.png")

        // 3. Bookmark the loaded page from the browser, then verify it in Bookmarks.
        composeRule.onNodeWithText("المتصفح").performClick()
        waitForNode(hasSetTextAction(), 10_000)
        composeRule.onNodeWithTag("bookmark_action").performClick()
        composeRule.onNodeWithText("الإشارات").performClick()
        waitForNode(hasText("example.com", substring = true), 15_000)
        capture("scenario-04-bookmarks.png")

        // 4. Reopen: recreate the activity and confirm history persists.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        waitForNode(hasSetTextAction(), 20_000)
        composeRule.onNodeWithText("السجل").performClick()
        waitForNode(hasText("example.com", substring = true), 15_000)
        capture("scenario-05-reopen-history.png")
    }

    private fun waitForNode(
        matcher: SemanticsMatcher,
        timeoutMillis: Long,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNode(matcher).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        if (screenshot == null || screenshot.width <= 0) {
            println("RUNTIME-SCREENSHOT: capture failed for $name")
            return
        }
        val path =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "image/png")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                "${Environment.DIRECTORY_DOWNLOADS}/$name (MediaStore)"
            } else {
                val out = File(context.getExternalFilesDir(null), name)
                FileOutputStream(out).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out.absolutePath
            }
        println("RUNTIME-SCREENSHOT: $path")
    }
}
