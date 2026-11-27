package com.alraad.core.common.fakes

import com.alraad.core.common.contracts.PlayerController
import com.alraad.core.common.contracts.StreamUrl

/**
 * Recording [PlayerController] for tests and development.
 * Captures every call, including the full [StreamUrl] with headers.
 */
class FakePlayerController : PlayerController {
    val playedStreams = mutableListOf<StreamUrl>()
    val seekPositionsMs = mutableListOf<Long>()
    var pauseCount = 0
        private set
    var releaseCount = 0
        private set

    override fun play(stream: StreamUrl) {
        playedStreams += stream
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
}
