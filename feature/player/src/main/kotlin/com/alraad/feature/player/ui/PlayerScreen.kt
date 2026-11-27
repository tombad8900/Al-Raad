package com.alraad.feature.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.player.impl.Media3PlayerController

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private const val SEEK_STEP_MS = 10_000L

/**
 * Double-tap seek target. Media3 reports C.TIME_UNSET for unknown duration, so the
 * upper clamp applies only when the duration is known; the lower bound always holds.
 */
internal fun seekTarget(
    positionMs: Long,
    deltaMs: Long,
    durationMs: Long,
): Long {
    val raw = positionMs + deltaMs
    return if (durationMs == C.TIME_UNSET) raw.coerceAtLeast(0L) else raw.coerceIn(0L, durationMs)
}

/**
 * RTL player screen (Issue #8, part B). Renders the controller's ExoPlayer via
 * Media3 PlayerView with its standard controls, adds double-tap seek (+-10s,
 * direction-aware under RTL) and a 0.5x-2x playback-speed menu. Releases the
 * player when the screen leaves composition.
 */
@Composable
fun PlayerScreen(
    controller: Media3PlayerController,
    stream: StreamUrl,
    modifier: Modifier = Modifier,
    drmLicenseUrl: String? = null,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gestures = rememberPlayerGestureController()
    var speed by remember { mutableFloatStateOf(1.0f) }
    var fullscreen by remember { mutableStateOf(false) }
    // Compose-observable handle: currentPlayer() is a plain field, so the player
    // created (or replaced) by play() is captured into state here, which schedules
    // the recomposition that rebinds PlayerView in AndroidView.update.
    var player by remember { mutableStateOf<Player?>(null) }
    val playbackError by controller.playbackError.collectAsState()

    // Fullscreen means real landscape rotation; leaving the screen restores the default.
    val activity = LocalContext.current as? Activity
    LaunchedEffect(fullscreen) {
        activity?.requestedOrientation =
            if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(stream) {
        controller.play(stream, drmLicenseUrl = drmLicenseUrl)
        player = controller.currentPlayer()
        onDispose {
            controller.release()
            player = null
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(player = player, gestures = gestures, isRtl = isRtl, speed = speed)
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
        ) {
            PlayerControlsRow(
                controller = controller,
                fullscreen = fullscreen,
                onToggleFullscreen = {
                    fullscreen = !fullscreen
                    gestures.setFullscreen(fullscreen)
                },
                onEnterPip = { gestures.enterPictureInPicture() },
                speed = speed,
                onSelectSpeed = { option ->
                    speed = option
                    player?.setPlaybackSpeed(option)
                },
            )
        }
        playbackError?.let { PlaybackErrorOverlay(message = it, isRtl = isRtl) }
    }
}

@Composable
private fun PlaybackErrorOverlay(
    message: String,
    isRtl: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = (if (isRtl) "تعذر تشغيل البث\n" else "Playback failed\n") + message,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SpeedMenu(
    speed: Float,
    onSelect: (Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(text = "${speed}x", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option}x") },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                    trailingIcon = {
                        if (option == speed) Icon(Icons.Default.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

private val QUALITY_OPTIONS = listOf<Int?>(null, 1080, 720, 480)

@Composable
private fun PlayerControlsRow(
    controller: Media3PlayerController,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    speed: Float,
    onSelectSpeed: (Float) -> Unit,
) {
    var qualityMenuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = onToggleFullscreen) {
        Icon(
            if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
            tint = Color.White,
        )
    }
    IconButton(onClick = onEnterPip) {
        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture in picture", tint = Color.White)
    }
    Box {
        TextButton(onClick = { qualityMenuOpen = true }) {
            Text("جودة / Quality", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = qualityMenuOpen, onDismissRequest = { qualityMenuOpen = false }) {
            QUALITY_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.let { "${it}p" } ?: "Auto") },
                    onClick = {
                        qualityMenuOpen = false
                        controller.setQuality(option?.let { Media3PlayerController.QualityLimit(it) })
                    },
                )
            }
        }
    }
    SpeedMenu(speed = speed, onSelect = onSelectSpeed)
}

@Composable
private fun PlayerSurface(
    player: Player?,
    gestures: PlayerGestureController,
    isRtl: Boolean,
    speed: Float,
) {
    AndroidView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(isRtl) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val up = -dragAmount / size.height.toFloat()
                        val volumeSide = if (isRtl) change.position.x < size.width / 2f else change.position.x >= size.width / 2f
                        if (volumeSide) gestures.adjustVolume(up) else gestures.adjustBrightness(up)
                    }
                }
                .pointerInput(isRtl) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val forward = if (isRtl) offset.x < size.width / 2f else offset.x >= size.width / 2f
                            val current = player ?: return@detectTapGestures
                            val delta = if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
                            current.seekTo(seekTarget(current.currentPosition, delta, current.duration))
                        },
                    )
                },
        factory = { context ->
            PlayerView(context).apply {
                useController = true
                this.player = player
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            player?.setPlaybackSpeed(speed)
        },
    )
}
