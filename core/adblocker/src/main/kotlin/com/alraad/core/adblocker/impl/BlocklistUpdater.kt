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

        data class Result(
            val updated: List<String>,
            val keptLastGood: List<String>,
            /** Downloaded fine but contains no rule this domain engine can apply (cosmetic, scriptlet, option rules only). */
            val unsupportedOnly: List<String> = emptyList(),
        )

        suspend fun updateAll(): Result =
            withContext(Dispatchers.IO) {
                val updated = mutableListOf<String>()
                val kept = mutableListOf<String>()
                val unsupported = mutableListOf<String>()
                for (source in BlocklistCatalog.sources) {
                    if (!store.isEnabled(source.id)) continue
                    val text = runCatching { fetcher(source.url) }.getOrNull()
                    val ok = text?.let { store.promote(source.id, it) } ?: false
                    if (ok && text != null) logSupport(source.id, text)
                    val noSupportedRules = !ok && text != null && hasOnlyUnsupportedRules(text)
                    when {
                        ok -> updated += source.id
                        noSupportedRules -> {
                            unsupported += source.id
                            logger.log(
                                DiagCategory.ADBLOCK,
                                DiagLevel.INFO,
                                "List has no rules this engine supports",
                                mapOf("list" to source.id),
                            )
                        }
                        else -> {
                            kept += source.id
                            logger.log(DiagCategory.ADBLOCK, DiagLevel.WARN, "List update kept last-known-good", mapOf("list" to source.id))
                        }
                    }
                }
                Result(updated, kept, unsupported)
            }

        private fun hasOnlyUnsupportedRules(text: String): Boolean {
            val parsed = AdblockPlusDomainParser.parseDetailed(text)
            return parsed.domains.isEmpty() && parsed.droppedByException == 0 &&
                parsed.unsupportedNetwork + parsed.cosmeticOrScriptlet > 0
        }

        /** Reports how much of the list this domain engine actually uses, so unsupported-heavy lists are visible. */
        private fun logSupport(
            id: String,
            text: String,
        ) {
            val parsed = AdblockPlusDomainParser.parseDetailed(text)
            logger.log(
                DiagCategory.ADBLOCK,
                DiagLevel.INFO,
                "List rule support",
                mapOf(
                    "list" to id,
                    "supportedDomains" to parsed.domains.size.toString(),
                    "unsupportedNetworkRules" to parsed.unsupportedNetwork.toString(),
                    "cosmeticOrScriptletRules" to parsed.cosmeticOrScriptlet.toString(),
                    "exceptionRules" to parsed.exceptions.toString(),
                    "blocksDroppedByException" to parsed.droppedByException.toString(),
                ),
            )
        }

        private fun download(url: String): String {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            try {
                check(conn.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${conn.responseCode}" }
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
