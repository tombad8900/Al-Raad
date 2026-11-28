package com.alraad.core.adblocker.impl

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists downloaded blocklists with last-known-good retention: a download is
 * parsed and sanity-checked before it atomically replaces the previous copy, so a
 * failed or malformed update never wipes working protection. Per-list toggles and
 * per-site exceptions live in SharedPreferences (frozen AppSettings contract is
 * not extended here).
 */
@Singleton
class BlocklistStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        /** Test hook: overrides the catalog's per-source promote floor. */
        internal var minDomains: Int? = null

        private val dir = File(context.filesDir, "blocklists")
        private val prefs = context.getSharedPreferences("adblock_lists", Context.MODE_PRIVATE)

        private fun effectiveMin(id: String): Int = minDomains ?: BlocklistCatalog.byId(id)?.minDomains ?: DEFAULT_MIN_DOMAINS

        private companion object {
            const val DEFAULT_MIN_DOMAINS = 100
        }

        fun isEnabled(id: String): Boolean = prefs.getBoolean("enabled_$id", BlocklistCatalog.byId(id)?.enabledByDefault ?: true)

        fun setEnabled(
            id: String,
            enabled: Boolean,
        ) {
            prefs.edit().putBoolean("enabled_$id", enabled).apply()
        }

        fun exceptions(): Set<String> = prefs.getStringSet("exceptions", emptySet()).orEmpty()

        fun setException(
            host: String,
            excepted: Boolean,
        ) {
            val updated = if (excepted) exceptions() + host.lowercase() else exceptions() - host.lowercase()
            prefs.edit().putStringSet("exceptions", updated).apply()
        }

        /** Parses candidate text and promotes it only when it yields enough domains. ABP-syntax lists are never promoted. */
        fun promote(
            id: String,
            text: String,
        ): Boolean {
            val inactive = BlocklistCatalog.byId(id)?.let(BlocklistCatalog::isActiveFormat) == false
            val domains = if (inactive) emptySet() else AdblockPlusDomainParser.parse(text)
            if (inactive || domains.size < effectiveMin(id)) return false
            val target = File(dir, "$id.domains")
            val tmp = File(dir, "$id.tmp")
            dir.mkdirs()
            tmp.writeText(domains.sorted().joinToString("\n"))
            check(tmp.renameTo(target)) { "atomic promote failed for $id" }
            return true
        }

        fun storedDomains(id: String): Set<String>? {
            val file = File(dir, "$id.domains")
            return if (file.isFile) file.readLines().filterTo(HashSet()) { it.isNotBlank() } else null
        }

        /** Domains from every enabled list that has a stored copy; empty when none downloaded yet. */
        fun activeDomains(): Set<String> {
            val merged = HashSet<String>()
            for (source in BlocklistCatalog.sources) {
                if (BlocklistCatalog.isActiveFormat(source) && isEnabled(source.id)) storedDomains(source.id)?.let(merged::addAll)
            }
            return merged
        }

        fun lastUpdated(id: String): Long = File(dir, "$id.domains").takeIf { it.isFile }?.lastModified() ?: 0L
    }
