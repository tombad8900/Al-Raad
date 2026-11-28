package com.alraad.feature.player

import androidx.media3.common.Player
import com.alraad.feature.player.ui.shouldKeepScreenOn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenOnTest {
    @Test fun screenStaysOnWhilePlayingOrBuffering() {
        assertTrue(shouldKeepScreenOn(playWhenReady = true, playbackState = Player.STATE_READY))
        assertTrue(shouldKeepScreenOn(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
    }

    @Test fun screenIsReleasedWhenPausedEndedOrIdle() {
        assertFalse(shouldKeepScreenOn(playWhenReady = false, playbackState = Player.STATE_READY))
        assertFalse(shouldKeepScreenOn(playWhenReady = true, playbackState = Player.STATE_ENDED))
        assertFalse(shouldKeepScreenOn(playWhenReady = true, playbackState = Player.STATE_IDLE))
    }
}
