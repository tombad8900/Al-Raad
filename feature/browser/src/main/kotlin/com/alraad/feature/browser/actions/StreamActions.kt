package com.alraad.feature.browser.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.PlaybackRequest
import com.alraad.core.common.contracts.PlayerController
import com.alraad.core.common.contracts.SavedStream
import com.alraad.core.common.contracts.SavedStreamsRepository
import com.alraad.core.common.contracts.StreamUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Play / Copy / Save-to-Library actions for a detected stream. */
@Singleton
class StreamActions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playerController: PlayerController,
        private val savedStreams: SavedStreamsRepository,
        private val logger: DiagnosticsLogger,
    ) {
        fun play(stream: StreamUrl) = play(PlaybackRequest.single(stream))

        fun play(request: PlaybackRequest) {
            val stream = request.selectedStream
            logger.log(
                DiagCategory.SNIFFER,
                DiagLevel.INFO,
                "Play requested",
                mapOf(
                    "format" to stream.format.name,
                    "sourceCount" to request.sources.size.toString(),
                    "subtitleCount" to request.subtitles.size.toString(),
                    "headerNames" to stream.headers.keys.joinToString(","),
                ),
            )
            playerController.play(request)
        }

        fun copyUrl(stream: StreamUrl) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("stream url", stream.url))
            logger.log(DiagCategory.SNIFFER, DiagLevel.INFO, "URL copied", mapOf("format" to stream.format.name))
        }

        suspend fun saveToLibrary(
            stream: StreamUrl,
            title: String,
            sourcePageUrl: String? = null,
        ): Long {
            val id =
                savedStreams.save(
                    SavedStream(
                        title = title,
                        stream = stream,
                        sourcePageUrl = sourcePageUrl,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            logger.log(DiagCategory.SNIFFER, DiagLevel.INFO, "Saved to library", mapOf("format" to stream.format.name, "id" to id.toString()))
            return id
        }
    }
