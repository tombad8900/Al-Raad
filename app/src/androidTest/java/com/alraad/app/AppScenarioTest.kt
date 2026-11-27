package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
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

        // 1. Browse: type an address and submit via IME Go. Deterministic sync at
        // the real boundary: the load must FINISH (progress indicator shown, then
        // gone) before leaving the tab, because leaving disposes the WebView and
        // would abandon an in-flight load, so no visit could ever be recorded.
        composeRule.onNode(hasSetTextAction()).performTextInput("example.com")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        runCatching { waitForNode(onNodeWithTagMatcher(), 10_000) } // best-effort: catch load start
        waitForNodeGone(onNodeWithTagMatcher(), 60_000) // then require load finished
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

    /**
     * Route regression (owner real-device finding): tapping the detected-stream bar
     * must reach the real PLAYER route, never the no-stream placeholder, and one tap
     * must start playback once (the controller's single-start guard covers the double
     * call from selection + PlayerScreen). Uses ExoPlayer's own public test media as
     * controlled authorized content; needs emulator network, and fails honestly if
     * the media cannot be fetched.
     */
    @Test fun detectedStreamTapReachesPlayerRoute() {
        composeRule.waitForIdle()
        waitForNode(hasSetTextAction(), 20_000)

        // Load a direct media URL so the sniffer detects it from the real request.
        composeRule.onNode(hasSetTextAction())
            .performTextInput("https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        waitForNode(hasText("\u25b6", substring = true), 45_000)
        capture("scenario-06-stream-bar.png")

        composeRule.onNodeWithText("\u25b6", substring = true).performClick()
        // The player route shows its controls; the no-stream placeholder must be absent.
        waitForNode(hasContentDescription("Fullscreen"), 15_000)
        composeRule.onNodeWithText(
            "\u0644\u0645 \u064a\u062a\u0645 \u0627\u062e\u062a\u064a\u0627\u0631 \u0628\u062b \u0628\u0639\u062f",
        ).assertDoesNotExist()
        capture("scenario-07-player-route.png")
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

    private fun onNodeWithTagMatcher(): SemanticsMatcher =
        SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, "page_loading")

    private fun waitForNodeGone(
        matcher: SemanticsMatcher,
        timeoutMillis: Long,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching { composeRule.onNode(matcher).assertIsDisplayed() }.isSuccess.not()
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
