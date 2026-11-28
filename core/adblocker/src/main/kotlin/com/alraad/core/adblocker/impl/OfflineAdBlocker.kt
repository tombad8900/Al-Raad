package com.alraad.core.adblocker.impl

import android.content.Context
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Domain blocker over the bundled hosts baseline (assets/blocklist.txt, see
 * assets/BLOCKLIST-LICENSE.txt) plus downloaded catalog lists merged via
 * [BlocklistStore]. Honors the persisted adBlockEnabled setting and per-site
 * exceptions. Blocking itself makes no network requests; list downloads happen
 * only in [BlocklistUpdater]. Raw blocked URLs are logged unredacted.
 */
@Singleton
class OfflineAdBlocker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val logger: DiagnosticsLogger,
        private val settings: SettingsRepository,
        private val store: BlocklistStore,
    ) : AdBlocker {
        private val loadMutex = Mutex()

        @Volatile private var index: DomainIndex? = null

        fun addToWhitelist(host: String) {
            store.setException(host, true)
        }

        fun removeFromWhitelist(host: String) {
            store.setException(host, false)
        }

        /** Drops the loaded index so the next lookup re-merges the store (after updates/toggles). */
        fun reload() {
            index = null
        }

        @Volatile private var enabledCache: Pair<Long, Boolean>? = null

        private suspend fun blockingEnabled(): Boolean {
            val now = System.currentTimeMillis()
            enabledCache?.let { if (now - it.first < 2_000) return it.second }
            val value = runCatching { settings.observeSettings().first().adBlockEnabled }.getOrDefault(true)
            enabledCache = now to value
            return value
        }

        private suspend fun ensureLoaded(): DomainIndex {
            index?.let { return it }
            return loadMutex.withLock {
                index ?: withContext(Dispatchers.IO) {
                    val text = context.assets.open("blocklist.txt").bufferedReader().use { it.readText() }
                    val merged =
                        AdblockPlusDomainParser.withoutGuarded(HostsParser.parse(text) + store.activeDomains(), store.activeGuardHosts())
                    DomainIndex(merged).also { loaded ->
                        logger.log(DiagCategory.ADBLOCK, DiagLevel.INFO, "Blocklist loaded", mapOf("domains" to loaded.size.toString()))
                    }
                }.also { index = it }
            }
        }

        override suspend fun shouldBlock(url: String): Boolean {
            if (!blockingEnabled()) return false
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            val exceptions = store.exceptions()
            val blocked =
                host != null && exceptions.none { host == it || host.endsWith(".$it") } &&
                    ensureLoaded().contains(host)
            if (blocked) logger.log(DiagCategory.ADBLOCK, DiagLevel.DEBUG, "Blocked", mapOf("url" to url))
            return blocked
        }

        override suspend fun cosmeticCss(host: String): String = if (blockingEnabled()) CosmeticRules.forHost(host) else ""
    }
