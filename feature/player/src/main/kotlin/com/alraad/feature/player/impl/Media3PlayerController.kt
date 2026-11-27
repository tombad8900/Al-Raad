package com.alraad.feature.player.impl

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.PlayerController
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media3-backed PlayerController (frozen contract). Every captured request header
 * (Referer, Cookie, tokens) is passed verbatim to the HTTP data source so playback
 * sends exactly what the sniffer saw. Raw URL/headers logged unredacted.
 */
@Singleton
class Media3PlayerController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val logger: DiagnosticsLogger,
    ) : PlayerController {
        private var player: ExoPlayer? = null

        /** Exposed for PlayerScreen rendering. */
        fun currentPlayer(): Player? = player

        override fun play(stream: StreamUrl) {
            logger.log(
                DiagCategory.PLAYER,
                DiagLevel.INFO,
                "Playing",
                mapOf("url" to stream.url, "format" to stream.format.name, "headers" to stream.headers.toString()),
            )
            val dataSourceFactory =
                DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(stream.headers)
            val mediaSource =
                when (stream.format) {
                    StreamFormat.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                    StreamFormat.DASH -> DashMediaSource.Factory(dataSourceFactory)
                    else -> ProgressiveMediaSource.Factory(dataSourceFactory)
                }.createMediaSource(MediaItem.fromUri(stream.url))
            release()
            player =
                ExoPlayer.Builder(context).build().apply {
                    setMediaSource(mediaSource)
                    prepare()
                    playWhenReady = true
                }
        }

        override fun pause() {
            player?.pause()
        }

        override fun seekTo(ms: Long) {
            player?.seekTo(ms)
        }

        override fun release() {
            player?.release()
            player = null
        }
    }
