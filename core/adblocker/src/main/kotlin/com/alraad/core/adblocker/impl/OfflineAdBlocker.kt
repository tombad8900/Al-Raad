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
enum class EngineStatus {
    NOT_BUILT,
    READY,
    NATIVE_UNAVAILABLE,

    /** A rebuild failed; the last-known-good engine (if any) keeps serving, otherwise nothing is blocked by rules. */
    BUILD_FAILED,
}

/** Owns the native engine handle; queries hold a read lock so a rebuild never destroys a handle in use. */
internal class EngineHolder {
    private val lock = ReentrantReadWriteLock()
    private var handle = 0L

    fun hasEngine(): Boolean = lock.read { handle != 0L }

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

@Suppress("TooManyFunctions") // contract overloads plus engine lifecycle (build, swap, status) share one lock and state
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

        /** Drops the loaded index and schedules an engine rebuild (after list updates or toggles). */
        fun reload() {
            index = null
            rebuildNeeded = true
        }

        private val engine = EngineHolder()

        @Volatile private var rebuildNeeded = true

        /** Test seams: engine construction and native availability. */
        internal var engineFactory: (String) -> Long = NativeAdblock::nativeCreate
        internal var nativeAvailable: () -> Boolean = NativeAdblock::isAvailable

        /** Why the rule engine is or is not active; ABP rules are never applied without it. */
        @Volatile var engineStatus: EngineStatus = EngineStatus.NOT_BUILT
            private set

        @Volatile private var baselineIndex: DomainIndex? = null

        private fun baselineText(): String = context.assets.open("blocklist.txt").bufferedReader().use { it.readText() }

        private suspend fun ensureEngine() {
            // While a rebuild runs, keep answering from the last-known-good engine instead of waiting for it.
            val servingOldEngine = engine.hasEngine() && loadMutex.isLocked
            if (rebuildNeeded && !servingOldEngine) {
                loadMutex.withLock {
                    if (rebuildNeeded) withContext(Dispatchers.IO) { rebuildEngine() }
                }
            }
        }

        private fun rebuildEngine() {
            if (!nativeAvailable()) {
                baselineIndex = DomainIndex(HostsParser.parse(baselineText()))
                engineStatus = EngineStatus.NATIVE_UNAVAILABLE
                rebuildNeeded = false
                logger.log(DiagCategory.ADBLOCK, DiagLevel.WARN, "Rule engine unavailable; applying bundled hosts only", emptyMap())
                return
            }
            // Cleared before reading the store so a reload() during the build schedules another rebuild.
            rebuildNeeded = false
            val rules = StringBuilder()
            HostsParser.parse(baselineText()).forEach { rules.append("||").append(it).append("^\n") }
            rules.append(store.activeEngineRules())
            val pending = store.listsPendingRefresh()
            if (pending.isNotEmpty()) {
                logger.log(
                    DiagCategory.ADBLOCK,
                    DiagLevel.WARN,
                    "Lists awaiting a fresh download; their lossy legacy cache is not applied",
                    mapOf("lists" to pending.joinToString(",")),
                )
            }
            val started = System.currentTimeMillis()
            val handle = runCatching { engineFactory(rules.toString()) }.getOrDefault(0L)
            if (handle != 0L) engine.replace(handle)
            engineStatus = if (handle != 0L) EngineStatus.READY else EngineStatus.BUILD_FAILED
            logger.log(
                DiagCategory.ADBLOCK,
                if (handle != 0L) DiagLevel.INFO else DiagLevel.WARN,
                "Rule engine $engineStatus",
                mapOf(
                    "buildMs" to (System.currentTimeMillis() - started).toString(),
                    "keptLastGood" to (handle == 0L && engine.hasEngine()).toString(),
                ),
            )
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
                    EngineStatus.READY, EngineStatus.BUILD_FAILED -> engine.query(url, sourceUrl, resourceType) ?: false
                    EngineStatus.NATIVE_UNAVAILABLE -> baselineIndex?.contains(host.orEmpty()) ?: false
                    else -> false
                }
            if (blocked) logger.log(DiagCategory.ADBLOCK, DiagLevel.DEBUG, "Blocked", mapOf("url" to url))
            return blocked
        }

        override suspend fun cosmeticCss(host: String): String = if (blockingEnabled()) CosmeticRules.forHost(host) else ""
    }
