package com.alraad.core.adblocker.impl

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloads enabled catalog lists and promotes them through [BlocklistStore]. */
class BlocklistUpdater
    @Inject
    constructor(
        private val store: BlocklistStore,
        private val logger: DiagnosticsLogger,
    ) {
        /** Test seam: replaces the network fetch; the default opens a real HTTP connection. */
        internal var fetcher: (String) -> String = ::download

        data class Result(val updated: List<String>, val keptLastGood: List<String>)

        suspend fun updateAll(): Result =
            withContext(Dispatchers.IO) {
                val updated = mutableListOf<String>()
                val kept = mutableListOf<String>()
                for (source in BlocklistCatalog.sources) {
                    if (!store.isEnabled(source.id)) continue
                    val ok = runCatching { fetcher(source.url) }.getOrNull()?.let { store.promote(source.id, it) } ?: false
                    (if (ok) updated else kept) += source.id
                    if (!ok) {
                        logger.log(DiagCategory.ADBLOCK, DiagLevel.WARN, "List update kept last-known-good", mapOf("list" to source.id))
                    }
                }
                Result(updated, kept)
            }

        internal fun download(url: String): String {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            try {
                check(conn.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${conn.responseCode}" }
                val bytes = conn.inputStream.use { it.readBytes() }
                val expected = conn.contentLengthLong
                // A connection cut mid-body can end quietly: a short body must never look like a complete list.
                check(expected < 0 || bytes.size.toLong() == expected) { "incomplete download ${bytes.size}/$expected" }
                return bytes.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }
    }
