package com.alraad.app.navigation

import com.alraad.core.common.contracts.StreamUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-destination handoff for #10: the library sets the active stream before
 * navigating to the player; a pending browse URL asks the browser tab to load a
 * page (e.g. a saved stream's source page). Process-scoped by design.
 */
@Singleton
class ActiveStreamHolder
    @Inject
    constructor() {
        @Volatile var activeStream: StreamUrl? = null
            private set

        @Volatile var pendingBrowseUrl: String? = null
            private set

        fun requestPlay(stream: StreamUrl) {
            activeStream = stream
        }

        fun requestBrowse(url: String) {
            pendingBrowseUrl = url
        }

        fun consumeBrowseRequest(): String? {
            val url = pendingBrowseUrl
            pendingBrowseUrl = null
            return url
        }
    }
