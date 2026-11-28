package com.alraad.feature.player

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.alraad.feature.player.ui.subtitleDialogBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleDialogTest {
    /**
     * Regression: the shipped dialog had no way to switch subtitles off. Media3 exposes no getter,
     * and a player without text tracks renders the same view either way, so this checks the
     * builder's configured flag. It does not prove how the dialog renders on a device.
     */
    @Test fun `subtitle dialog builder enables the disable option`() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), android.R.style.Theme_Material_Dialog)
        val player = ExoPlayer.Builder(context).build()
        try {
            val builder = subtitleDialogBuilder(context, player)
            val field = builder.javaClass.getDeclaredField("showDisableOption").apply { isAccessible = true }
            assertEquals(true, field.getBoolean(builder))
        } finally {
            player.release()
        }
    }
}
