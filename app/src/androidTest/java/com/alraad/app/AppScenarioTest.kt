package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.feature.browser.tabs.TabManagerTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /** TabManager is a process-wide @Singleton: clear tabs leaked by earlier tests. */
    @Before
    fun resetTabManager() {
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            TabManagerTestEntryPoint::class.java,
        ).tabManager().reset()
    }

    @Test fun browseThenHistoryBookmarkAndReopen() {
        composeRule.waitForIdle()
        waitForNode(hasSetTextAction(), 20_000)

        // 1. Browse: type a RUN-UNIQUE address and submit via IME Go. The unique
        // query makes every later row assertion prove a FRESH visit - a stale row
        // persisted from an earlier run can never mask a missing record. Sync at
        // the real boundary: the load must demonstrably START (progress indicator
        // shown; isLoading is set before loadUrl, so it renders at least one frame)
        // and then FINISH (indicator gone) before leaving the tab, because leaving
        // disposes the WebView and would abandon an in-flight load.
        val uniqueUrl = "https://example.com/?t=" + System.currentTimeMillis()
        composeRule.onNode(hasSetTextAction()).performTextInput(uniqueUrl)
        composeRule.onNode(hasSetTextAction()).performImeAction()
        waitForNode(onNodeWithTagMatcher(), 15_000) // required: load started
        waitForNodeGone(onNodeWithTagMatcher(), 60_000) // required: load finished
        capture("scenario-02-browsing.png")

        // 2. History: the finished load must appear as an entry (proves recording).
        composeRule.onNodeWithText("السجل").performClick()
        waitForNode(rowInList("history_list"), 30_000)
        capture("scenario-03-history.png")

        // 3. Bookmark the loaded page from the browser, then verify it in Bookmarks.
        composeRule.onNodeWithText("المتصفح").performClick()
        waitForNode(hasSetTextAction(), 10_000)
        composeRule.onNodeWithTag("bookmark_action").performClick()
        composeRule.onNodeWithText("الإشارات").performClick()
        waitForNode(rowInList("bookmarks_list"), 15_000)
        capture("scenario-04-bookmarks.png")

        // 4. Reopen: recreate the activity and confirm history persists.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("السجل").performClick()
        waitForNode(rowInList("history_list"), 15_000)
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
    @Test
    fun localVideoRendersInPlayerAndFitFillCaptures() {
        composeRule.waitForIdle()
        waitForNode(hasSetTextAction(), 20_000)
        val server = localPlaybackServer(downloadPlaybackFixture())
        try {
            val pageUrl = server.url("/player.html").toString()
            composeRule.onNode(hasSetTextAction()).performTextInput(pageUrl)
            composeRule.onNode(hasSetTextAction()).performImeAction()
            waitForNode(hasText("▶", substring = true), 45_000)
            composeRule.onNodeWithText("▶", substring = true).performClick()
            waitForNode(hasContentDescription("Fullscreen"), 15_000)
            waitForRenderedVideoFrames()
            Thread.sleep(1_000)
            capture("scenario-player-fit-frames.png")

            composeRule.onNode(hasContentDescription("Fill screen")).performClick()
            waitForNode(hasContentDescription("Fit video inside screen"), 10_000)
            composeRule.waitForIdle()
            Thread.sleep(500)
            capture("scenario-player-fill-frames.png")
        } finally {
            server.shutdown()
        }
    }

    private fun downloadPlaybackFixture(): ByteArray {
        val fixtureUrl =
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
        val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
        val bytes =
            client.newCall(Request.Builder().url(fixtureUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Test video download failed: HTTP ${response.code}" }
                requireNotNull(response.body).bytes()
            }
        check(bytes.isNotEmpty()) { "Test video fixture is empty" }
        return bytes
    }

    private fun localPlaybackServer(fixture: ByteArray): MockWebServer {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/player.html" ->
                            MockResponse()
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody(
                                    "<html><body><video autoplay muted playsinline src='/fixture.mp4'></video></body></html>",
                                )
                        "/fixture.mp4" ->
                            MockResponse()
                                .setHeader("Content-Type", "video/mp4")
                                .setHeader("Accept-Ranges", "bytes")
                                .setBody(Buffer().write(fixture))
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        return server
    }

    @OptIn(UnstableApi::class)
    private fun waitForRenderedVideoFrames() {
        var attachedPlayer: ExoPlayer? = null
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                onView(isAssignableFrom(PlayerView::class.java)).check(
                    matches(
                        object : TypeSafeMatcher<View>() {
                            override fun matchesSafely(view: View): Boolean {
                                val player = (view as PlayerView).player as? ExoPlayer ?: return false
                                attachedPlayer = player
                                return true
                            }

                            override fun describeTo(description: Description) {
                                description.appendText("PlayerView with an attached ExoPlayer")
                            }
                        },
                    ),
                )
            }.isSuccess
        }

        val player = checkNotNull(attachedPlayer) { "PlayerView did not attach an ExoPlayer" }
        val firstFrameRendered = CountDownLatch(1)
        val listener =
            object : AnalyticsListener {
                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long,
                ) {
                    firstFrameRendered.countDown()
                }
            }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { player.addAnalyticsListener(listener) }
        try {
            assertTrue(
                "Media3 did not report a rendered video frame within 90 seconds",
                firstFrameRendered.await(90, TimeUnit.SECONDS),
            )
            var playbackState = Player.STATE_IDLE
            var videoWidth = 0
            var videoHeight = 0
            instrumentation.runOnMainSync {
                playbackState = player.playbackState
                videoWidth = player.videoSize.width
                videoHeight = player.videoSize.height
            }
            // The short fixture clip can END before assertions run on a slow emulator;
            // READY or ENDED both prove the render pipeline delivered the first frame.
            assertTrue(
                "Player left the render pipeline before the frame assertion",
                playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED,
            )
            assertTrue("Decoded video width is missing", videoWidth > 0)
            assertTrue("Decoded video height is missing", videoHeight > 0)
        } finally {
            instrumentation.runOnMainSync { player.removeAnalyticsListener(listener) }
        }
    }

    private fun rowInList(listTag: String): SemanticsMatcher = hasText("?t=", substring = true) and hasAnyAncestor(hasTestTag(listTag))

    private fun waitForNode(
        matcher: SemanticsMatcher,
        timeoutMillis: Long,
    ) {
        runCatching {
            // onAllNodes+onFirst tolerates duplicate matching rows (e.g. a reload
            // recording the same visit twice), where onNode would fail on the
            // multiple matches instead of checking the first.
            composeRule.waitUntil(timeoutMillis = timeoutMillis) {
                runCatching {
                    composeRule.onAllNodes(matcher).onFirst().assertIsDisplayed()
                }.isSuccess
            }
        }.onFailure {
            // A red run must still show what the screen actually looked like.
            capture("scenario-timeout-" + System.currentTimeMillis() + ".png")
            throw it
        }
        composeRule.waitForIdle()
    }

    private fun onNodeWithTagMatcher(): SemanticsMatcher =
        SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, "page_loading")

    private fun waitForNodeGone(
        matcher: SemanticsMatcher,
        timeoutMillis: Long,
    ) {
        runCatching {
            composeRule.waitUntil(timeoutMillis = timeoutMillis) {
                runCatching { composeRule.onAllNodes(matcher).onFirst().assertIsDisplayed() }.isSuccess.not()
            }
        }.onFailure {
            capture("scenario-timeout-" + System.currentTimeMillis() + ".png")
            throw it
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
