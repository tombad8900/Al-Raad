package com.alraad.feature.player.impl

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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

        /**
         * Test seam for player construction, so unit tests can force a deterministic
         * build failure instead of relying on asynchronous ExoPlayer errors. Not used
         * by production wiring (Hilt keeps the default).
         */
        internal var playerFactory: (Context) -> ExoPlayer = { ctx -> ExoPlayer.Builder(ctx).build() }

        // Last stream handed to play(), so the PLAYER nav route can render it.
        private val _currentStream = kotlinx.coroutines.flow.MutableStateFlow<StreamUrl?>(null)
        val currentStream: kotlinx.coroutines.flow.StateFlow<StreamUrl?> = _currentStream

        /** Exposed for PlayerScreen rendering. */
        fun currentPlayer(): Player? = player

        /** Last playback/build failure, surfaced in the player UI instead of a crash. */
        private val _playbackError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val playbackError: kotlinx.coroutines.flow.StateFlow<String?> = _playbackError

        /** Requested quality ceiling; null means adaptive/auto. */
        data class QualityLimit(val maxHeight: Int)

        override fun play(stream: StreamUrl) = play(stream, drmLicenseUrl = stream.drmLicenseUrl, quality = null)

        /**
         * Issue #8 part D: standard DRM playback (Widevine when drmLicenseUrl is
         * provided) and quality selection (cap renditions by max height).
         */
        fun play(
            stream: StreamUrl,
            drmLicenseUrl: String? = null,
            quality: QualityLimit? = null,
        ) {
            // Single-start guard: stream selection (the nav callback) and PlayerScreen's
            // own DisposableEffect both call play() for one tap; the second call for the
            // same stream must not tear down and rebuild a healthy player. A previous
            // failure leaves player == null, so retries still rebuild.
            if (_currentStream.value == stream && player != null) {
                logger.log(DiagCategory.PLAYER, DiagLevel.DEBUG, "Play already active", mapOf("url" to stream.url))
                return
            }
            logger.log(
                DiagCategory.PLAYER,
                DiagLevel.INFO,
                "Playing",
                mapOf("url" to stream.url, "format" to stream.format.name, "headers" to stream.headers.toString()),
            )
            // Hand the stream to the PLAYER route BEFORE any fallible construction:
            // a build failure must surface as an in-app error, never as a crash that
            // restarts the process onto the player route with no stream (the owner hit
            // exactly that as the stale issue-8 placeholder).
            _playbackError.value = null
            release()
            _currentStream.value = stream
            try {
                val mediaSource = buildMediaSource(stream, drmLicenseUrl)
                player =
                    playerFactory(context).apply {
                        addListener(
                            object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) {
                                    logger.log(
                                        DiagCategory.PLAYER,
                                        DiagLevel.ERROR,
                                        "Playback error",
                                        mapOf(
                                            "url" to stream.url,
                                            "code" to error.errorCodeName,
                                            "message" to (error.message ?: ""),
                                        ),
                                    )
                                    _playbackError.value = error.errorCodeName + ": " + (error.message ?: "")
                                }
                            },
                        )
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
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                // Intentionally total: any failure (checked, runtime, linkage) must become
                // an in-app error state, never a process crash onto the player route.
                logger.log(
                    DiagCategory.PLAYER,
                    DiagLevel.ERROR,
                    "Player build failed",
                    mapOf("url" to stream.url, "error" to (t.message ?: t.javaClass.simpleName)),
                )
                _playbackError.value = t.message ?: t.javaClass.simpleName
                player = null
            }
        }

        private fun buildMediaSource(
            stream: StreamUrl,
            drmLicenseUrl: String?,
        ) = run {
            val dataSourceFactory =
                DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(stream.headers)
            val mediaItem =
                MediaItem.Builder().setUri(stream.url).apply {
                    val effectiveLicenseUrl = drmLicenseUrl ?: stream.drmLicenseUrl
                    if (effectiveLicenseUrl != null) {
                        // License-request headers stay on the DRM config, distinct from the
                        // stream headers applied to the media data source above.
                        setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                .setLicenseUri(effectiveLicenseUrl)
                                .setLicenseRequestHeaders(stream.drmLicenseRequestHeaders)
                                .build(),
                        )
                    }
                }.build()
            when (stream.format) {
                StreamFormat.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                StreamFormat.DASH -> DashMediaSource.Factory(dataSourceFactory)
                else -> ProgressiveMediaSource.Factory(dataSourceFactory)
            }.createMediaSource(mediaItem)
        }

        /** Live quality change: applies (or clears) the rendition cap on the active player. */
        fun setQuality(quality: QualityLimit?) {
            val current = player ?: return
            current.trackSelectionParameters =
                if (quality == null) {
                    TrackSelectionParameters.Builder(context).build()
                } else {
                    TrackSelectionParameters.Builder(context)
                        .setMaxVideoSize(Int.MAX_VALUE, quality.maxHeight)
                        .build()
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
            _currentStream.value = null
        }
    }
