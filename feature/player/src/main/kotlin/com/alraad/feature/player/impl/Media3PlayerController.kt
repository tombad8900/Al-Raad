package com.alraad.feature.player.impl

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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

/**
 * Media3-backed PlayerController. Side-loaded subtitle requests receive only a
 * strict safe-header allowlist; credentials from the video request never reach
 * subtitle URLs or their redirects.
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3PlayerController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val logger: DiagnosticsLogger,
    ) : PlayerController {
        private var player: ExoPlayer? = null

        /** Test seam for player construction; production uses the default ExoPlayer builder. */
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

        override fun play(stream: StreamUrl) {
            play(PlaybackRequest.single(stream))
        }

        override fun play(request: PlaybackRequest) {
            play(request, drmLicenseUrl = request.selectedStream.drmLicenseUrl, quality = null)
        }

        /** Compatibility overload for callers that still supply a DRM URL explicitly. */
        fun play(
            stream: StreamUrl,
            drmLicenseUrl: String? = stream.drmLicenseUrl,
            quality: QualityLimit? = null,
        ) {
            play(PlaybackRequest.single(stream), drmLicenseUrl, quality)
        }

        fun play(
            request: PlaybackRequest,
            drmLicenseUrl: String? = request.selectedStream.drmLicenseUrl,
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
                                        mapOf("code" to error.errorCodeName, "format" to stream.format.name),
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
                    mapOf("errorType" to t.javaClass.simpleName, "format" to stream.format.name),
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
            val dataSourceFactory = buildDataSourceFactory(request, stream)
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
            // DefaultMediaSourceFactory is required for MediaItem subtitle configurations.
            // Media3 1.5.1 does not support sideloaded subtitles for multi-period DASH.
            return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }

        private fun buildDataSourceFactory(
            request: PlaybackRequest,
            stream: StreamUrl,
        ): DataSource.Factory {
            val userAgent =
                stream.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                    ?: request.subtitles.firstNotNullOfOrNull { track ->
                        track.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                    }
            val upstreamFactory =
                DefaultHttpDataSource.Factory().apply {
                    if (!userAgent.isNullOrBlank()) setUserAgent(userAgent)
                }
            return ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
                val headers = headersForRequest(dataSpec.uri, stream.headers, request.subtitles)
                dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
            }
        }

        /** Routes per-URI headers; subtitle URLs never inherit video credentials. */
        internal fun headersForRequest(
            uri: Uri,
            streamHeaders: Map<String, String>,
            subtitles: List<SubtitleTrack>,
        ): Map<String, String> {
            val subtitle = subtitles.firstOrNull { Uri.parse(it.url).toString() == uri.toString() }
            return if (subtitle != null) {
                subtitle.requestHeaders.filterKeys { it.lowercase() in SAFE_SUBTITLE_HEADER_NAMES }
            } else {
                streamHeaders.filterKeys {
                    !it.equals("User-Agent", ignoreCase = true) &&
                        !it.equals("Range", ignoreCase = true) &&
                        !it.equals("Accept-Encoding", ignoreCase = true)
                }
            }
        }

        /** Live quality change preserves text-track selection and other parameters. */
        fun setQuality(quality: QualityLimit?) {
            val current = player ?: return
            val builder = current.trackSelectionParameters.buildUpon()
            if (quality == null) {
                builder.clearVideoSizeConstraints()
            } else {
                builder.setMaxVideoSize(Int.MAX_VALUE, quality.maxHeight)
            }
            current.trackSelectionParameters = builder.build()
        }

        override fun pause() {
            player?.pause()
        }

        override fun seekTo(ms: Long) {
            player?.seekTo(ms)
        }

        /** Frees resources but keeps the request so PlayerScreen can rebuild after recreation. */
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
            val SAFE_SUBTITLE_HEADER_NAMES = setOf("accept", "accept-language", "cache-control", "pragma")
        }
    }
