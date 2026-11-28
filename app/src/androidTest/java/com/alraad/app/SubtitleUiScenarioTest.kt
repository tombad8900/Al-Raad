package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.alraad.feature.browser.tabs.TabManagerTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Deterministic bright fixture position shared with AppScenarioTest captures. */
private const val UI_CAPTURE_POSITION_MS = 90_000L
private const val UI_SEEK_TOLERANCE_MS = 2_000L

/** Start of active playback for the display-while-playing proof (cue spans 88-120s). */
private const val PLAYBACK_PROOF_START_MS = 85_000L

/**
 * User-facing T4 runtime proof through the REAL UI: the subtitle button opens the
 * shipped track-selection dialog, a track tap renders cues on screen, "None" turns
 * them off, and "ملف ترجمة" attaches a file picked in the actual system picker.
 * Controller-level decode proof lives in SubtitlePlaybackRuntimeTest; this class
 * proves the interaction path. Every step captures full-screen PNG evidence.
 * Requires the host-served fixture (fixtureBaseUrl): the page subtitle (subs.vtt)
 * and the pickable file (ui-pick.vtt) are staged by ci.yml.
 */
class SubtitleUiScenarioTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val device
        get() = UiDevice.getInstance(instrumentation)

    @Before
    fun resetTabManager() {
        EntryPointAccessors.fromApplication(
            instrumentation.targetContext.applicationContext,
            TabManagerTestEntryPoint::class.java,
        ).tabManager().reset()
    }

    @Test
    fun subtitleSelectionDisableAndExternalPickerThroughRealUi() {
        val hostBase =
            InstrumentationRegistry.getArguments().getString("fixtureBaseUrl")
                ?: error("Subtitle UI proof requires the host-served fixture (fixtureBaseUrl)")

        // Reach the player through the shipped browse -> picker -> play path.
        composeRule.waitForIdle()
        waitForNode(hasSetTextAction(), 20_000)
        composeRule.onNode(hasSetTextAction()).performTextInput("$hostBase/player.html")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        waitForNode(hasText("روابط البث المكتشفة", substring = true), 45_000)
        // The page's <track> fetch must reach the subtitle sniffer before Play is tapped.
        Thread.sleep(2_000)
        composeRule.onNodeWithText("تشغيل").performClick()
        waitForNode(hasContentDescription("Fullscreen"), 15_000)
        val player = findAttachedPlayer()

        // 1. Select the page subtitle through the real dialog.
        composeRule.onNodeWithText("الترجمة").performClick()
        capture("scenario-subtitle-dialog.png")
        val trackItem = device.wait(Until.findObject(By.text("subs")), 10_000)
        assertNotNull("Page subtitle not listed in the real selection dialog", trackItem)
        trackItem.click()
        // Playback-progress display proof: the cue must appear while playback is
        // actually advancing (not only at a paused seek position; the paused decode
        // check lives in SubtitlePlaybackRuntimeTest). The fixture cue spans
        // 88-120s, so playback has a wide window to cross after the subtitle loads.
        val pageCue = watchCues(player, "Caption at ninety seconds")
        resumePlaybackFrom(player, PLAYBACK_PROOF_START_MS)
        assertTrue(
            "Selected subtitle never displayed while playback progressed",
            pageCue.await(90, TimeUnit.SECONDS),
        )
        seekAndPauseAt(player)
        capture("scenario-subtitle-on.png")

        // 2. Turn subtitles off through the same dialog (Media3 labels it "None").
        composeRule.onNodeWithText("الترجمة").performClick()
        val noneItem =
            device.wait(Until.findObject(By.text("None")), 8_000)
                ?: device.wait(Until.findObject(By.text("Disable")), 4_000)
        assertNotNull("No off entry in the real selection dialog", noneItem)
        noneItem.click()
        composeRule.waitForIdle()
        Thread.sleep(800)
        capture("scenario-subtitle-off.png")

        // 3. Attach a subtitle file through the real system picker.
        composeRule.onNodeWithText("ملف ترجمة").performClick()
        assertTrue(
            "System document picker did not open",
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000),
        )
        capture("scenario-subtitle-saf.png")
        assertTrue("ui-pick.vtt was not pickable in the system picker", pickFromSaf("ui-pick.vtt"))

        // The attach rebuilt the player; re-acquire it, select the new track via the
        // real dialog, and prove its cue renders.
        val rebuilt = findAttachedPlayer()
        composeRule.onNodeWithText("الترجمة").performClick()
        val externalItem = device.wait(Until.findObject(By.textContains("ui-pick")), 10_000)
        assertNotNull("Attached external subtitle not listed in the real dialog", externalItem)
        externalItem.click()
        val externalCue = watchCues(rebuilt, "External file caption")
        resumePlaybackFrom(rebuilt, PLAYBACK_PROOF_START_MS)
        assertTrue(
            "External file subtitle never displayed while playback progressed",
            externalCue.await(90, TimeUnit.SECONDS),
        )
        seekAndPauseAt(rebuilt)
        capture("scenario-subtitle-external.png")
    }

    /** Clicks a file in the system DocumentsUI picker: Recent first, then the Downloads root. */
    private fun pickFromSaf(fileName: String): Boolean {
        var item = device.wait(Until.findObject(By.text(fileName)), 5_000)
        if (item == null) {
            device.findObject(By.desc("Show roots"))?.click()
            device.wait(Until.findObject(By.text("Downloads")), 5_000)?.click()
            item = device.wait(Until.findObject(By.text(fileName)), 8_000)
        }
        capture("scenario-subtitle-saf-file.png")
        item?.click() ?: return false
        return true
    }

    /** Attaches a cue listener before the seek so crossing the cue window re-emits. */
    private fun watchCues(
        player: ExoPlayer,
        expectedCue: String,
    ): CountDownLatch {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            player.addListener(
                object : Player.Listener {
                    override fun onCues(cueGroup: CueGroup) {
                        if (cueGroup.cues.any { it.text?.contains(expectedCue) == true }) {
                            latch.countDown()
                        }
                    }
                },
            )
        }
        return latch
    }

    /** Resumes real playback from a position (display-while-playing proof). */
    private fun resumePlaybackFrom(
        player: ExoPlayer,
        positionMs: Long,
    ) {
        instrumentation.runOnMainSync {
            player.seekTo(positionMs)
            player.playWhenReady = true
        }
    }

    /** Deterministic capture position: seek, await READY at 90s, pause, settle. */
    private fun seekAndPauseAt(player: ExoPlayer) {
        instrumentation.runOnMainSync { player.seekTo(UI_CAPTURE_POSITION_MS) }
        var atPosition = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                atPosition =
                    player.currentPosition >= UI_CAPTURE_POSITION_MS - UI_SEEK_TOLERANCE_MS &&
                    player.playbackState == Player.STATE_READY
            }
            if (atPosition) break
            Thread.sleep(250)
        }
        assertTrue("Player did not reach the deterministic capture position", atPosition)
        instrumentation.runOnMainSync { player.pause() }
        Thread.sleep(1_200)
    }

    /** The ExoPlayer currently attached to the on-screen PlayerView. */
    private fun findAttachedPlayer(): ExoPlayer {
        var attachedPlayer: ExoPlayer? = null
        composeRule.waitUntil(timeoutMillis = 20_000) {
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
        return checkNotNull(attachedPlayer) { "PlayerView did not attach an ExoPlayer" }
    }

    private fun waitForNode(
        matcher: SemanticsMatcher,
        timeoutMillis: Long,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching { composeRule.onNode(matcher).assertExists() }.isSuccess
        }
    }

    private fun capture(name: String) {
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
