package com.alraad.feature.player

import androidx.media3.common.C
import com.alraad.feature.player.ui.seekTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeekTargetTest {
    @Test fun `clamps into range when duration is known`() {
        assertEquals(70_000L, seekTarget(60_000L, 10_000L, 120_000L))
        assertEquals(120_000L, seekTarget(115_000L, 10_000L, 120_000L))
        assertEquals(0L, seekTarget(5_000L, -10_000L, 120_000L))
    }

    @Test fun `unknown duration applies lower bound only`() {
        // C.TIME_UNSET is negative; clamping the upper bound to 0 would reset the seek.
        assertEquals(70_000L, seekTarget(60_000L, 10_000L, C.TIME_UNSET))
        assertEquals(0L, seekTarget(5_000L, -10_000L, C.TIME_UNSET))
    }
}
