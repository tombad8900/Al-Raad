package com.alraad.feature.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitScreen
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.alraad.core.common.contracts.PlaybackRequest
import com.alraad.feature.player.impl.Media3PlayerController
import com.alraad.feature.player.impl.externalSubtitleTrack

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
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    controller: Media3PlayerController,
    playbackRequest: PlaybackRequest,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gestures = rememberPlayerGestureController()
    var speed by remember { mutableFloatStateOf(1.0f) }
    var fullscreen by remember { mutableStateOf(false) }
    // Compose-observable handle: currentPlayer() is a plain field, so the player
    // created (or replaced) by play() is captured into state here, which schedules
    // the recomposition that rebinds PlayerView in AndroidView.update.
    var player by remember { mutableStateOf<Player?>(null) }
    val playbackError by controller.playbackError.collectAsState()

    FullscreenOrientationEffect(fullscreen)
    PauseOnStopEffect(controller)
    var fillScreen by rememberFillScreenState()

    DisposableEffect(playbackRequest) {
        controller.play(playbackRequest)
        player = controller.currentPlayer()
        onDispose {
            // release() frees resources but preserves the selection, so a recreate
            // or back-stack restore rebuilds playback instead of landing on the
            // no-stream placeholder (owner real-device finding).
            controller.release()
            player = null
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(player = player, gestures = gestures, isRtl = isRtl, speed = speed, fillScreen = fillScreen)
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
        ) {
            PlayerControlsRow(
                controller = controller,
                display =
                    DisplayControls(
                        fullscreen = fullscreen,
                        onToggleFullscreen = {
                            fullscreen = !fullscreen
                            gestures.setFullscreen(fullscreen)
                        },
                        fillScreen = fillScreen,
                        onToggleFill = { fillScreen = !fillScreen },
                    ),
                onEnterPip = { gestures.enterPictureInPicture() },
                player = player,
                subtitles = rememberSubtitleControls(controller, player),
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

private data class DisplayControls(
    val fullscreen: Boolean,
    val onToggleFullscreen: () -> Unit,
    val fillScreen: Boolean,
    val onToggleFill: () -> Unit,
)

@Composable
private fun rememberSubtitleControls(
    controller: Media3PlayerController,
    player: Player?,
): SubtitleControls {
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val displayName =
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            externalSubtitleTrack(uri.toString(), displayName)?.let(controller::addExternalSubtitle)
        }
    return SubtitleControls(
        onSelect = {
            player?.let { current ->
                TrackSelectionDialogBuilder(context, "الترجمة", current, C.TRACK_TYPE_TEXT)
                    .build()
                    .show()
            }
        },
        onPickFile = { picker.launch(arrayOf("*/*")) },
    )
}

/** Subtitle actions: embedded-track dialog and local subtitle file picker. */
private data class SubtitleControls(
    val onSelect: () -> Unit,
    val onPickFile: () -> Unit,
)

@Composable
private fun PlayerControlsRow(
    controller: Media3PlayerController,
    display: DisplayControls,
    onEnterPip: () -> Unit,
    player: Player?,
    subtitles: SubtitleControls,
    speed: Float,
    onSelectSpeed: (Float) -> Unit,
) {
    var qualityMenuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = display.onToggleFullscreen) {
        Icon(
            if (display.fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            contentDescription = if (display.fullscreen) "Exit fullscreen" else "Fullscreen",
            tint = Color.White,
        )
    }
    IconButton(onClick = onEnterPip) {
        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture in picture", tint = Color.White)
    }
    TextButton(onClick = subtitles.onSelect, enabled = player != null) {
        Text("الترجمة", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
    TextButton(onClick = subtitles.onPickFile, enabled = player != null) {
        Text("ملف ترجمة", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
    IconButton(onClick = display.onToggleFill) {
        Icon(
            if (display.fillScreen) Icons.Default.FitScreen else Icons.Default.AspectRatio,
            contentDescription = if (display.fillScreen) "Fit video inside screen" else "Fill screen",
            tint = Color.White,
        )
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
    fillScreen: Boolean,
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
                // Deterministic initial scale: Fit from the first layout, so the
                // first frames never render zoomed/cropped while dimensions settle.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.resizeMode =
                if (fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            player?.setPlaybackSpeed(speed)
        },
    )
}

/** Pauses playback when the app is backgrounded so audio never keeps playing off-screen (owner directive item 3). Resume stays manual. */
@Composable
private fun PauseOnStopEffect(controller: Media3PlayerController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) controller.pause()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Display mode state: Fit keeps the whole frame inside the screen (default, prevents the
 * zoomed/cropped first frames); Fill fills the screen and may crop (owner items 1b/1c).
 */
@Composable
private fun rememberFillScreenState() = rememberSaveable { mutableStateOf(false) }

/** Fullscreen means real landscape rotation; leaving the screen restores the default orientation. */
@Composable
private fun FullscreenOrientationEffect(fullscreen: Boolean) {
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
}
