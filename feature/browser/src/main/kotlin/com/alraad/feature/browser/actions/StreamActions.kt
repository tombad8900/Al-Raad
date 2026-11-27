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
        fun play(stream: StreamUrl) {
            logger.log(DiagCategory.SNIFFER, DiagLevel.INFO, "Play requested", mapOf("url" to stream.url))
            playerController.play(stream)
        }

        fun copyUrl(stream: StreamUrl) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("stream url", stream.url))
            logger.log(DiagCategory.SNIFFER, DiagLevel.INFO, "URL copied", mapOf("url" to stream.url))
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
            logger.log(DiagCategory.SNIFFER, DiagLevel.INFO, "Saved to library", mapOf("url" to stream.url, "id" to id.toString()))
            return id
        }
    }
