package com.alraad.feature.player

import com.alraad.feature.player.ui.PlayerGestureController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGestureControllerTest {
    @Test fun `fractional volume drags accumulate into whole steps`() {
        // max = 8 steps; four tiny upward drags of 0.0625 each (binary-exact) must
        // total +2 steps, not 0 truncated per callback.
        var remainder = 0f
        var applied = 0
        repeat(4) {
            val (rem, steps) = PlayerGestureController.accumulateSteps(remainder, 0.0625f, 8)
            remainder = rem
            applied += steps
        }
        assertEquals(2, applied)
        assertEquals(0f, remainder)
    }

    @Test fun `downward drags accumulate negative steps`() {
        val (rem, steps) = PlayerGestureController.accumulateSteps(0f, -0.25f, 10)
        assertEquals(-2, steps)
        assertTrue(rem < 0f)
    }

    @Test fun `PiP returns false without a host activity`() {
        assertFalse(PlayerGestureController(null).enterPictureInPicture())
    }
}
