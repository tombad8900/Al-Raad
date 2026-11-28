package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.compose.ui.test.ComposeTimeoutException
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

/** Recognizable bright fixture position for deterministic Fit/Fill captures. */
private const val CAPTURE_POSITION_MS = 90_000L

/** Acceptable undershoot when awaiting the deterministic capture position. */
private const val SEEK_TOLERANCE_MS = 2_000L

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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val tabManager =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                TabManagerTestEntryPoint::class.java,
            ).tabManager()
        // reset() destroys pooled WebViews, which must happen on the main thread.
        instrumentation.runOnMainSync { tabManager.reset() }
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
        // Prefer a host-served fixture (CI passes fixtureBaseUrl): keeps the ~20MB clip
        // and its serving buffers OUT of the instrumented process. The in-process
        // MockWebServer path remains as the local fallback, with bounded range copies.
        val hostBase = InstrumentationRegistry.getArguments().getString("fixtureBaseUrl")
        val server = if (hostBase == null) localPlaybackServer(downloadPlaybackFixture()) else null
        try {
            val pageUrl = hostBase?.let { "$it/player.html" } ?: server!!.url("/player.html").toString()
            composeRule.onNode(hasSetTextAction()).performTextInput(pageUrl)
            composeRule.onNode(hasSetTextAction()).performImeAction()
            // T2 picker UI (post #73): a detected stream shows the picker section with
            // a Play button instead of the old minimal bar.
            waitForNode(
                hasText(
                    "\u0631\u0648\u0627\u0628\u0637 \u0627\u0644\u0628\u062b \u0627\u0644\u0645\u0643\u062a\u0634\u0641\u0629",
                    substring = true,
                ),
                45_000,
            )
            capture("scenario-06-stream-picker.png")

            composeRule.onNodeWithText("\u062a\u0634\u063a\u064a\u0644").performClick()
            // The player route shows its controls; the no-stream placeholder must be absent.
            waitForNode(hasContentDescription("Fullscreen"), 15_000)
            waitForRenderedVideoFrames().also { seekAndPauseForCapture(it) }
            capture("scenario-player-fit-frames.png")

            composeRule.onNode(hasContentDescription("Fill screen")).performClick()
            waitForNode(hasContentDescription("Fit video inside screen"), 10_000)
            composeRule.waitForIdle()
            Thread.sleep(500)
            capture("scenario-player-fill-frames.png")
        } finally {
            server?.shutdown()
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
                        "/fixture.mp4" -> fixtureResponse(request, fixture)
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        return server
    }

    /**
     * Serves the fixture with Range support: the ~20MB clip held in memory is copied per
     * request body, and concurrent full-body copies OOM the shared app/test process (seen
     * on main run 46100573832). Range replies copy only the requested window.
     */
    private fun fixtureResponse(
        request: RecordedRequest,
        fixture: ByteArray,
    ): MockResponse {
        val base =
            MockResponse()
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Accept-Ranges", "bytes")
        val range = request.getHeader("Range")?.takeIf { it.startsWith("bytes=") } ?: return base.setBody(Buffer().write(fixture))
        val parts = range.removePrefix("bytes=").split("-")
        val start = (parts[0].toLongOrNull() ?: 0L).coerceIn(0L, (fixture.size - 1).toLong())
        val requestedEnd = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (fixture.size - 1).toLong()
        val end = requestedEnd.coerceIn(start, (fixture.size - 1).toLong())
        return base
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${fixture.size}")
            .setBody(Buffer().write(fixture.copyOfRange(start.toInt(), end.toInt() + 1)))
    }

    @OptIn(UnstableApi::class)
    private fun waitForRenderedVideoFrames(): ExoPlayer {
        var attachedPlayer: ExoPlayer? = null
        var lastMatcherFailure: Throwable? = null
        try {
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
                }.onFailure { lastMatcherFailure = it }.isSuccess
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "No PlayerView with an attached ExoPlayer within 15 s. Last matcher failure: $lastMatcherFailure",
                timeout,
            )
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
            // The short fixture clip can END before assertions run on a slow emulator;
            // READY or ENDED both prove the render pipeline delivered the first frame.
            // Poll briefly: a loaded emulator can transiently re-enter BUFFERING right
            // after the first frame, and the state name/error is recorded on failure.
            var playbackState = Player.STATE_IDLE
            var videoWidth = 0
            var videoHeight = 0
            var playbackError: String? = null
            val stateDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            do {
                instrumentation.runOnMainSync {
                    playbackState = player.playbackState
                    videoWidth = player.videoSize.width
                    videoHeight = player.videoSize.height
                    playbackError = player.playerError?.errorCodeName
                }
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) break
                Thread.sleep(250)
            } while (System.nanoTime() < stateDeadline)
            assertTrue(
                "Player left the render pipeline before the frame assertion " +
                    "(state=" + playbackState + ", error=" + playbackError + ")",
                playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED,
            )
            assertTrue("Decoded video width is missing", videoWidth > 0)
            assertTrue("Decoded video height is missing", videoHeight > 0)
        } finally {
            instrumentation.runOnMainSync { player.removeAnalyticsListener(listener) }
        }
        return player
    }

    /**
     * Deterministic visual evidence: seek to a known recognizable (non-dark) fixture
     * position, wait until the player reports that position in READY, then pause so the
     * Fit and Fill captures show the same frame. Early-clip frames are near-black, which
     * made prior captures ambiguous (run 46104292921 fit capture).
     */
    private fun seekAndPauseForCapture(player: ExoPlayer) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { player.seekTo(CAPTURE_POSITION_MS) }
        var atPosition = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                atPosition =
                    player.currentPosition >= CAPTURE_POSITION_MS - SEEK_TOLERANCE_MS &&
                    player.playbackState == Player.STATE_READY
            }
            if (atPosition) break
            Thread.sleep(250)
        }
        assertTrue("Player did not reach the deterministic capture position", atPosition)
        instrumentation.runOnMainSync { player.pause() }
        Thread.sleep(1_200)
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
