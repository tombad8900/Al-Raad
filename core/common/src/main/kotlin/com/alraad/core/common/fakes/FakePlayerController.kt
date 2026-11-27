package com.alraad.core.common.fakes

import com.alraad.core.common.contracts.PlaybackRequest
import com.alraad.core.common.contracts.PlayerController
import com.alraad.core.common.contracts.StreamUrl

/**
 * Recording [PlayerController] for tests and development.
 * Captures every call, including the full [StreamUrl] with headers.
 */
class FakePlayerController : PlayerController {
    val playedStreams = mutableListOf<StreamUrl>()
    var lastPlaybackRequest: PlaybackRequest? = null
        private set
    val seekPositionsMs = mutableListOf<Long>()
    var pauseCount = 0
        private set
    var releaseCount = 0
        private set

    override fun play(stream: StreamUrl) {
        playedStreams += stream
        lastPlaybackRequest = PlaybackRequest.single(stream)
    }

    override fun play(request: PlaybackRequest) {
        lastPlaybackRequest = request
        playedStreams += request.selectedStream
    }

    override fun pause() {
        pauseCount++
    }

    override fun seekTo(ms: Long) {
        seekPositionsMs += ms
    }

    override fun release() {
        releaseCount++
    }

    var stopCount = 0
        private set

    override fun stop() {
        stopCount++
    }
}
