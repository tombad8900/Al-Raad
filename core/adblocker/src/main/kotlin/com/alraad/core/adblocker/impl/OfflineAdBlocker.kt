package com.alraad.core.adblocker.impl

import android.content.Context
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** State of the rule engine behind the context-aware overload. */
enum class EngineStatus { NOT_BUILT, READY, NATIVE_UNAVAILABLE, BUILD_FAILED }

/** Owns the native engine handle; queries hold a read lock so a rebuild never destroys a handle in use. */
internal class EngineHolder {
    private val lock = ReentrantReadWriteLock()
    private var handle = 0L

    fun replace(newHandle: Long) =
        lock.write {
            val old = handle
            handle = newHandle
            if (old != 0L) NativeAdblock.nativeDestroy(old)
        }

    fun query(
        url: String,
        sourceUrl: String,
        type: String,
    ): Boolean? =
        lock.read {
            if (handle == 0L) null else NativeAdblock.nativeShouldBlock(handle, url, sourceUrl, type)
        }
}

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
            engineStatus = EngineStatus.NOT_BUILT
        }

        private val engine = EngineHolder()

        /** Why the rule engine is or is not active; ABP rules are never applied without it. */
        @Volatile var engineStatus: EngineStatus = EngineStatus.NOT_BUILT
            private set

        @Volatile private var baselineIndex: DomainIndex? = null

        private fun baselineText(): String = context.assets.open("blocklist.txt").bufferedReader().use { it.readText() }

        private suspend fun ensureEngine() {
            if (engineStatus != EngineStatus.NOT_BUILT) return
            loadMutex.withLock {
                if (engineStatus != EngineStatus.NOT_BUILT) return
                withContext(Dispatchers.IO) {
                    if (!NativeAdblock.isAvailable()) {
                        baselineIndex = DomainIndex(HostsParser.parse(baselineText()))
                        engineStatus = EngineStatus.NATIVE_UNAVAILABLE
                        logger.log(DiagCategory.ADBLOCK, DiagLevel.WARN, "Rule engine unavailable; applying bundled hosts only", emptyMap())
                        return@withContext
                    }
                    val rules = StringBuilder()
                    HostsParser.parse(baselineText()).forEach { rules.append("||").append(it).append("^\n") }
                    rules.append(store.activeEngineRules())
                    val started = System.currentTimeMillis()
                    val handle = runCatching { NativeAdblock.nativeCreate(rules.toString()) }.getOrDefault(0L)
                    engine.replace(handle)
                    engineStatus = if (handle != 0L) EngineStatus.READY else EngineStatus.BUILD_FAILED
                    logger.log(
                        DiagCategory.ADBLOCK,
                        if (handle != 0L) DiagLevel.INFO else DiagLevel.WARN,
                        "Rule engine $engineStatus",
                        mapOf("buildMs" to (System.currentTimeMillis() - started).toString()),
                    )
                }
            }
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
                    val merged = HostsParser.parse(text) + store.activeDomains()
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

        override suspend fun shouldBlock(
            url: String,
            sourceUrl: String,
            resourceType: String,
        ): Boolean {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            val eligible =
                host != null && blockingEnabled() && store.exceptions().none { host == it || host.endsWith(".$it") }
            if (!eligible) return false
            ensureEngine()
            val blocked =
                when (engineStatus) {
                    EngineStatus.READY -> engine.query(url, sourceUrl, resourceType) ?: false
                    EngineStatus.NATIVE_UNAVAILABLE -> baselineIndex?.contains(host.orEmpty()) ?: false
                    else -> false
                }
            if (blocked) logger.log(DiagCategory.ADBLOCK, DiagLevel.DEBUG, "Blocked", mapOf("url" to url))
            return blocked
        }

        override suspend fun cosmeticCss(host: String): String = if (blockingEnabled()) CosmeticRules.forHost(host) else ""
    }
