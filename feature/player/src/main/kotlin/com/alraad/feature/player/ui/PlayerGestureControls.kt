package com.alraad.feature.player.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Resolves the hosting Activity from a Compose context chain. */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Player gesture side effects (Issue #8, part C): vertical drags adjust volume
 * (right half) or screen brightness (left half); fullscreen toggles system bars;
 * PiP uses Activity.enterPictureInPictureMode on API 26+.
 */
class PlayerGestureController(
    private val activity: Activity?,
) {
    private val audioManager: AudioManager? = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var volumeRemainder = 0f

    /** delta > 0 means the finger moved up. Fractional steps accumulate across callbacks. */
    fun adjustVolume(delta: Float) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val (remainder, steps) = accumulateSteps(volumeRemainder, delta, max)
        volumeRemainder = remainder
        if (steps == 0) return
        val target = (current + steps).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    /** delta > 0 means the finger moved up. Writes window brightness 0.05-1.0. */
    fun adjustBrightness(delta: Float) {
        val window = activity?.window ?: return
        val attrs = window.attributes
        val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
        attrs.screenBrightness = (current + delta).coerceIn(0.05f, 1.0f)
        window.attributes = attrs
    }

    fun setFullscreen(enabled: Boolean) {
        val window = activity?.window ?: return
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Enters PiP on API 26+. Returns the platform result: false when there is no
     * host Activity, the platform is too old, Android rejects the request, or the
     * call throws (e.g. PiP unsupported on the device).
     */
    fun enterPictureInPicture(): Boolean {
        val host = activity?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O }
        return host?.let {
            try {
                it.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: IllegalStateException) {
                android.util.Log.w("PlayerGestureController", "PiP unavailable", e)
                false
            }
        } ?: false
    }

    companion object {
        /**
         * Accumulates fractional volume movement: returns the leftover remainder and
         * the whole steps to apply, so gradual swipes are never truncated away.
         */
        internal fun accumulateSteps(
            remainder: Float,
            delta: Float,
            max: Int,
        ): Pair<Float, Int> {
            val total = remainder + delta * max
            val steps = total.toInt()
            return (total - steps) to steps
        }

        /** Kept for callers that gate brightness writes behind the system permission. */
        fun canWriteBrightnessSettings(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
    }
}

@Composable
fun rememberPlayerGestureController(): PlayerGestureController {
    val context = LocalContext.current
    return remember(context) { PlayerGestureController(context.findActivity()) }
}
