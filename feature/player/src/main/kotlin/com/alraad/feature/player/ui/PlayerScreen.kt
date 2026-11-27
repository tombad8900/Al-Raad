package com.alraad.feature.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var speed by remember { mutableFloatStateOf(1.0f) }
    // Compose-observable handle: currentPlayer() is a plain field, so the player
    // created (or replaced) by play() is captured into state here, which schedules
    // the recomposition that rebinds PlayerView in AndroidView.update.
    var player by remember { mutableStateOf<Player?>(null) }

    DisposableEffect(stream) {
        controller.play(stream)
        player = controller.currentPlayer()
        onDispose {
            controller.release()
            player = null
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxSize()
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
        ) {
            SpeedMenu(
                speed = speed,
                onSelect = { option ->
                    speed = option
                    player?.setPlaybackSpeed(option)
                },
            )
        }
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
