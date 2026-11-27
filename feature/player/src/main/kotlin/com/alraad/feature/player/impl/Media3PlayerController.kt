package com.alraad.feature.player.impl

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.PlaybackRequest
import com.alraad.core.common.contracts.PlayerController
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.core.common.contracts.SubtitleTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Media3 player with request-scoped headers and page-scoped external subtitle tracks. */
@Singleton
class Media3PlayerController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val logger: DiagnosticsLogger,
    ) : PlayerController {
        private var player: ExoPlayer? = null

        /** Test seam for deterministic player-construction failure coverage. */
        internal var playerFactory: (Context) -> ExoPlayer = { ctx -> ExoPlayer.Builder(ctx).build() }

        private val _currentStream = kotlinx.coroutines.flow.MutableStateFlow<StreamUrl?>(null)
        val currentStream: kotlinx.coroutines.flow.StateFlow<StreamUrl?> = _currentStream

        private val _currentPlaybackRequest =
            kotlinx.coroutines.flow.MutableStateFlow<PlaybackRequest?>(null)
        val currentPlaybackRequest: kotlinx.coroutines.flow.StateFlow<PlaybackRequest?> = _currentPlaybackRequest

        fun currentPlayer(): Player? = player

        private val _playbackError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val playbackError: kotlinx.coroutines.flow.StateFlow<String?> = _playbackError

        data class QualityLimit(val maxHeight: Int)

        override fun play(stream: StreamUrl) =
            play(PlaybackRequest.single(stream), drmLicenseUrl = stream.drmLicenseUrl, quality = null)

        override fun play(request: PlaybackRequest) =
            play(request, drmLicenseUrl = request.selectedStream.drmLicenseUrl, quality = null)

        fun play(
            stream: StreamUrl,
            drmLicenseUrl: String? = null,
            quality: QualityLimit? = null,
        ) = play(PlaybackRequest.single(stream), drmLicenseUrl, quality)

        fun play(
            request: PlaybackRequest,
            drmLicenseUrl: String? = null,
            quality: QualityLimit? = null,
        ) {
            val stream = request.selectedStream
            if (_currentPlaybackRequest.value == request && player != null) {
                logger.log(
                    DiagCategory.PLAYER,
                    DiagLevel.DEBUG,
                    "Play already active",
                    mapOf("format" to stream.format.name),
                )
                return
            }
            logger.log(
                DiagCategory.PLAYER,
                DiagLevel.INFO,
                "Playing",
                mapOf(
                    "format" to stream.format.name,
                    "sourceCount" to request.sources.size.toString(),
                    "subtitleCount" to request.subtitles.size.toString(),
                    "headerNames" to stream.headers.keys.joinToString(","),
                ),
            )
            _playbackError.value = null
            release()
            _currentPlaybackRequest.value = request
            _currentStream.value = stream
            try {
                val mediaSource = buildMediaSource(request, drmLicenseUrl)
                player =
                    playerFactory(context).apply {
                        addListener(
                            object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) {
                                    logger.log(
                                        DiagCategory.PLAYER,
                                        DiagLevel.ERROR,
                                        "Playback error",
                                        mapOf("code" to error.errorCodeName),
                                    )
                                    _playbackError.value = error.errorCodeName
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
                logger.log(
                    DiagCategory.PLAYER,
                    DiagLevel.ERROR,
                    "Player build failed",
                    mapOf("errorType" to t.javaClass.simpleName),
                )
                _playbackError.value = t.javaClass.simpleName
                player = null
            }
        }

        private fun buildMediaSource(
            request: PlaybackRequest,
            drmLicenseUrl: String?,
        ): MediaSource {
            val stream = request.selectedStream
            val dataSourceFactory = buildDataSourceFactory(stream, request.subtitles)
            val subtitleConfigurations =
                request.subtitles.map { track ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                        .setMimeType(track.mimeType)
                        .setLanguage(track.language)
                        .setLabel(track.label)
                        .build()
                }
            val mediaItem =
                MediaItem.Builder()
                    .setUri(stream.url)
                    .apply {
                        when (stream.format) {
                            StreamFormat.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                            StreamFormat.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                            else -> Unit
                        }
                        if (subtitleConfigurations.isNotEmpty()) {
                            setSubtitleConfigurations(subtitleConfigurations)
                        }
                        val effectiveLicenseUrl = drmLicenseUrl ?: stream.drmLicenseUrl
                        if (effectiveLicenseUrl != null) {
                            setDrmConfiguration(
                                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                    .setLicenseUri(effectiveLicenseUrl)
                                    .setLicenseRequestHeaders(stream.drmLicenseRequestHeaders)
                                    .build(),
                            )
                        }
                    }.build()
            return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }

        private fun buildDataSourceFactory(
            stream: StreamUrl,
            subtitles: List<SubtitleTrack>,
        ): DataSource.Factory {
            val upstreamFactory = DefaultHttpDataSource.Factory()
            return ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
                val headers = headersForRequest(dataSpec.uri, stream.headers, subtitles)
                dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
            }
        }

        /**
         * Subtitle requests receive only their own allowlisted headers. Video credentials
         * remain attached only to media/manifest/segment requests, never to subtitle URLs.
         */
        internal fun headersForRequest(
            uri: Uri,
            streamHeaders: Map<String, String>,
            subtitles: List<SubtitleTrack>,
        ): Map<String, String> {
            val requestedUrl = uri.normalizeScheme().toString()
            val subtitle = subtitles.firstOrNull {
                Uri.parse(it.url).normalizeScheme().toString() == requestedUrl
            }
            return if (subtitle != null) {
                subtitle.requestHeaders.filterKeys { it.lowercase() in SAFE_SUBTITLE_HEADER_NAMES }
            } else {
                streamHeaders.filterKeys {
                    !it.equals("Range", ignoreCase = true) &&
                        !it.equals("Accept-Encoding", ignoreCase = true)
                }
            }
        }

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

        /** Frees resources but keeps the selected request for player-route recreation. */
        override fun release() {
            player?.release()
            player = null
        }

        override fun stop() {
            release()
            _currentStream.value = null
            _currentPlaybackRequest.value = null
        }

        private companion object {
            val SAFE_SUBTITLE_HEADER_NAMES = setOf(
                "accept",
                "accept-language",
                "cache-control",
                "pragma",
                "user-agent",
                "origin",
                "referer",
            )
        }
    }
