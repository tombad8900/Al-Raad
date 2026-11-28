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

/**
 * Media3-backed PlayerController (frozen contract). Every captured request header
 * (Referer, Cookie, tokens) is passed verbatim to media requests so playback sends
 * exactly what the sniffer saw; external subtitle URLs receive only their own
 * allowlisted headers, never video credentials. Raw URL/headers logged unredacted.
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

        /** Last rich playback request (sources + page-scoped subtitles), when played via PlaybackRequest. */
        private val _currentPlaybackRequest =
            kotlinx.coroutines.flow.MutableStateFlow<PlaybackRequest?>(null)
        val currentPlaybackRequest: kotlinx.coroutines.flow.StateFlow<PlaybackRequest?> = _currentPlaybackRequest

        /** Info-level PLAYER log entry, shared by the file-level playback extensions. */
        internal fun logPlaybackEvent(
            message: String,
            details: Map<String, String>,
        ) = logger.log(DiagCategory.PLAYER, DiagLevel.INFO, message, details)

        /** Exposed for PlayerScreen rendering. */
        fun currentPlayer(): Player? = player

        /** Last playback/build failure, surfaced in the player UI instead of a crash. */
        private val _playbackError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val playbackError: kotlinx.coroutines.flow.StateFlow<String?> = _playbackError

        /** Requested quality ceiling; null means adaptive/auto. */
        data class QualityLimit(val maxHeight: Int)

        override fun play(stream: StreamUrl) = play(stream, drmLicenseUrl = stream.drmLicenseUrl, quality = null)

        override fun play(request: PlaybackRequest) = play(request, drmLicenseUrl = request.selectedStream.drmLicenseUrl, quality = null)

        /**
         * Issue #8 part D: standard DRM playback (Widevine when drmLicenseUrl is
         * provided) and quality selection (cap renditions by max height).
         */
        fun play(
            stream: StreamUrl,
            drmLicenseUrl: String? = null,
            quality: QualityLimit? = null,
        ) = play(PlaybackRequest.single(stream), drmLicenseUrl, quality)

        /**
         * Rich playback: page/tab-scoped external subtitles are attached to the Media3
         * item, and every request is resolved to the correct header set per URL.
         */
        fun play(
            request: PlaybackRequest,
            drmLicenseUrl: String? = null,
            quality: QualityLimit? = null,
        ) {
            val stream = request.selectedStream
            // Single-start guard: stream selection (the nav callback) and PlayerScreen's
            // own DisposableEffect both call play() for one tap; the second call for the
            // same request must not tear down and rebuild a healthy player. A previous
            // failure leaves player == null, so retries still rebuild.
            if (_currentPlaybackRequest.value == request && player != null) {
                logger.log(DiagCategory.PLAYER, DiagLevel.DEBUG, "Play already active", mapOf("url" to stream.url))
                return
            }
            logger.log(
                DiagCategory.PLAYER,
                DiagLevel.INFO,
                "Playing",
                mapOf(
                    "url" to stream.url,
                    "format" to stream.format.name,
                    "headers" to stream.headers.toString(),
                    "subtitleCount" to request.subtitles.size.toString(),
                ),
            )
            // Hand the stream to the PLAYER route BEFORE any fallible construction:
            // a build failure must surface as an in-app error, never as a crash that
            // restarts the process onto the player route with no stream (the owner hit
            // exactly that as the stale issue-8 placeholder).
            _playbackError.value = null
            release()
            _currentStream.value = stream
            _currentPlaybackRequest.value = request
            try {
                val mediaSource = PlaybackMediaSources.build(request, drmLicenseUrl)
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

        override fun stop() {
            release()
            _currentStream.value = null
            _currentPlaybackRequest.value = null
        }
    }

/**
 * Attaches a user-picked local subtitle file to the active playback. The request
 * is rebuilt with the track appended; position and play state carry over.
 */
fun Media3PlayerController.addExternalSubtitle(track: SubtitleTrack) {
    val request = currentPlaybackRequest.value ?: return
    if (request.subtitles.any { it.url == track.url }) return
    logPlaybackEvent(
        "External subtitle attached",
        mapOf("url" to track.url, "mimeType" to track.mimeType),
    )
    rebuildPreservingPlayback { it.copy(subtitles = it.subtitles + track) }
}

/**
 * Dispose decision for PlayerScreen: true only while [request] is still the active
 * request, so a controller-side rebuild (source switch, subtitle attach) is never
 * mistaken for leaving the screen.
 */
internal fun Media3PlayerController.isActiveRequest(request: PlaybackRequest): Boolean = currentPlaybackRequest.value == request

/** Manual source switch (T5): rebuilds on another detected source, preserving position. */
fun Media3PlayerController.switchSource(sourceIndex: Int) {
    val request = currentPlaybackRequest.value ?: return
    if (sourceIndex !in request.sources.indices || sourceIndex == request.selectedSourceIndex) return
    logPlaybackEvent(
        "Source switch",
        mapOf("from" to request.selectedStream.url, "to" to request.sources[sourceIndex].url),
    )
    rebuildPreservingPlayback { it.copy(selectedSourceIndex = sourceIndex) }
}

private fun Media3PlayerController.rebuildPreservingPlayback(update: (PlaybackRequest) -> PlaybackRequest) {
    val current = currentPlayer()
    val position = current?.currentPosition ?: 0L
    val wasPlaying = current?.playWhenReady == true
    play(update(currentPlaybackRequest.value ?: return))
    currentPlayer()?.let {
        it.seekTo(position)
        it.playWhenReady = wasPlaying
    }
}

/** Live quality change: applies (or clears) the rendition cap on the active player. */
fun Media3PlayerController.setQuality(quality: Media3PlayerController.QualityLimit?) {
    val current = currentPlayer() ?: return
    current.trackSelectionParameters =
        current.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, quality?.maxHeight ?: Int.MAX_VALUE)
            .build()
}

/**
 * Routes request headers per URL: subtitle requests receive only their own allowlisted
 * headers; video credentials stay on media/manifest/segment requests, never subtitles.
 */
internal object PlaybackRequestHeaderRouter {
    fun dataSourceFactory(
        stream: StreamUrl,
        subtitles: List<SubtitleTrack>,
    ): DataSource.Factory {
        val upstreamFactory = DefaultHttpDataSource.Factory()
        return ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val headers = headersForRequest(dataSpec.uri, stream.headers, subtitles)
            dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
        }
    }

    internal fun headersForRequest(
        uri: Uri,
        streamHeaders: Map<String, String>,
        subtitles: List<SubtitleTrack>,
    ): Map<String, String> {
        val requestedUrl = uri.normalizeScheme().toString()
        val subtitle =
            subtitles.firstOrNull {
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

    private val SAFE_SUBTITLE_HEADER_NAMES =
        setOf(
            "accept",
            "accept-language",
            "cache-control",
            "pragma",
            "user-agent",
            "origin",
            "referer",
        )
}

/** Builds the Media3 [MediaSource] for a request: format, DRM config, external subtitles. */
internal object PlaybackMediaSources {
    fun build(
        request: PlaybackRequest,
        drmLicenseUrl: String?,
    ): MediaSource {
        val stream = request.selectedStream
        val dataSourceFactory = PlaybackRequestHeaderRouter.dataSourceFactory(stream, request.subtitles)
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
                        // License-request headers stay on the DRM config, distinct from the
                        // stream headers applied to media requests by the data source.
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
}
