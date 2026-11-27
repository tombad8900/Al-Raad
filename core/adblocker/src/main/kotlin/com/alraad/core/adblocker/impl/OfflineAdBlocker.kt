package com.alraad.core.adblocker.impl

import android.content.Context
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fully offline ad blocker: a bundled hosts-format blocklist (assets/blocklist.txt,
 * see assets/BLOCKLIST-LICENSE.txt) loaded once, plus an in-memory whitelist.
 * No network requests are ever made. Raw blocked URLs are logged unredacted.
 */
@Singleton
class OfflineAdBlocker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val logger: DiagnosticsLogger,
    ) : AdBlocker {
        private val loadMutex = Mutex()

        @Volatile private var index: DomainIndex? = null
        private val whitelist = HashSet<String>()

        fun addToWhitelist(host: String) {
            whitelist += host.lowercase()
        }

        fun removeFromWhitelist(host: String) {
            whitelist -= host.lowercase()
        }

        private suspend fun ensureLoaded(): DomainIndex {
            index?.let { return it }
            return loadMutex.withLock {
                index ?: withContext(Dispatchers.IO) {
                    val text = context.assets.open("blocklist.txt").bufferedReader().use { it.readText() }
                    DomainIndex(HostsParser.parse(text)).also { loaded ->
                        logger.log(DiagCategory.ADBLOCK, DiagLevel.INFO, "Blocklist loaded", mapOf("domains" to loaded.size.toString()))
                    }
                }.also { index = it }
            }
        }

        override suspend fun shouldBlock(url: String): Boolean {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            val blocked =
                host != null &&
                    whitelist.none { host == it || host.endsWith(".$it") } &&
                    ensureLoaded().contains(host)
            if (blocked) logger.log(DiagCategory.ADBLOCK, DiagLevel.DEBUG, "Blocked", mapOf("url" to url))
            return blocked
        }

        override fun cosmeticCss(host: String): String = CosmeticRules.forHost(host)
    }
