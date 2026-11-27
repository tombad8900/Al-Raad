package com.alraad.feature.player.impl

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
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

        /** Requested quality ceiling; null means adaptive/auto. */
        data class QualityLimit(val maxHeight: Int)

        override fun play(stream: StreamUrl) = play(stream, drmLicenseUrl = null, quality = null)

        /**
         * Issue #8 part D: standard DRM playback (Widevine when drmLicenseUrl is
         * provided) and quality selection (cap renditions by max height).
         */
        fun play(
            stream: StreamUrl,
            drmLicenseUrl: String? = null,
            quality: QualityLimit? = null,
        ) {
            logger.log(
                DiagCategory.PLAYER,
                DiagLevel.INFO,
                "Playing",
                mapOf("url" to stream.url, "format" to stream.format.name, "headers" to stream.headers.toString()),
            )
            val dataSourceFactory =
                DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(stream.headers)
            val mediaItem =
                MediaItem.Builder().setUri(stream.url).apply {
                    if (drmLicenseUrl != null) {
                        setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                .setLicenseUri(drmLicenseUrl)
                                .build(),
                        )
                    }
                }.build()
            val mediaSource =
                when (stream.format) {
                    StreamFormat.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                    StreamFormat.DASH -> DashMediaSource.Factory(dataSourceFactory)
                    else -> ProgressiveMediaSource.Factory(dataSourceFactory)
                }.createMediaSource(mediaItem)
            release()
            player =
                ExoPlayer.Builder(context).build().apply {
                    if (quality != null) {
                        trackSelectionParameters =
                            TrackSelectionParameters.Builder(context)
                                .setMaxVideoSize(Int.MAX_VALUE, quality.maxHeight)
                                .build()
                    }
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
