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
@Suppress("TooManyFunctions") // one store for list files, toggles and exceptions; the file helpers are tiny
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
            const val MIN_KEPT_FRACTION = 0.5
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

        /** Enough domains, and not far smaller than the stored copy (that looks like a cut-off download). */
        private fun plausibleUpdate(
            id: String,
            count: Int,
        ): Boolean {
            val previous = storedDomains(id)?.size ?: 0
            return count >= effectiveMin(id) && (previous == 0 || count >= previous * MIN_KEPT_FRACTION)
        }

        /** Parses candidate text and promotes it only when it yields enough domains. */
        fun promote(
            id: String,
            text: String,
        ): Boolean {
            val domains = AdblockPlusDomainParser.parse(text)
            if (!plausibleUpdate(id, domains.size)) return false
            dir.mkdirs()
            val rawTmp = File(dir, "$id.raw.tmp")
            // One file per list, replaced by a single rename: the stored copy is either entirely the old list or
            // entirely the new one. Domains are derived from this same text, so the two can never disagree.
            val promoted =
                runCatching {
                    rawTmp.writeText(text)
                    check(rawTmp.renameTo(rawFile(id))) { "atomic promote failed for $id" }
                }.isSuccess
            if (!promoted) rawTmp.delete()
            return promoted
        }

        private fun rawFile(id: String) = File(dir, "$id.raw")

        /** Pre-raw installs kept only a parsed domain list; it is used until the next successful update. */
        private fun legacyDomainsFile(id: String) = File(dir, "$id.domains")

        fun storedDomains(id: String): Set<String>? {
            val raw = rawFile(id)
            val legacy = legacyDomainsFile(id)
            return when {
                raw.isFile -> AdblockPlusDomainParser.parse(raw.readText())
                legacy.isFile -> legacy.readLines().filterTo(HashSet()) { it.isNotBlank() }
                else -> null
            }
        }

        /** Domains from every enabled list that has a stored copy; empty when none downloaded yet. */
        fun activeDomains(): Set<String> {
            val merged = HashSet<String>()
            for (source in BlocklistCatalog.sources) {
                if (isEnabled(source.id)) storedDomains(source.id)?.let(merged::addAll)
            }
            return merged
        }

        /**
         * Rule text for the engine. A stored raw list is used as is (hosts-format lists are converted to
         * `||domain^`). A pre-raw install only has parsed domains: those are exact for hosts-format lists, but for
         * Adblock Plus lists they are a lossy subset (they dropped rule options, so a `$third-party` or path-scoped
         * rule on a normal site became a whole-domain block). That ABP cache is NOT fed to the engine; see
         * [listsPendingRefresh].
         */
        fun activeEngineRules(): String {
            val out = StringBuilder()
            for (source in BlocklistCatalog.sources) {
                if (!isEnabled(source.id)) continue
                val raw = rawFile(source.id)
                val legacy = legacyDomainsFile(source.id)
                when {
                    raw.isFile && source.hostsFormat ->
                        HostsParser.parse(
                            raw.readText(),
                        ).forEach { out.append("||").append(it).append("^\n") }
                    raw.isFile -> out.append(raw.readText()).append('\n')
                    legacy.isFile && source.hostsFormat -> storedDomains(source.id)?.forEach { out.append("||").append(it).append("^\n") }
                }
            }
            return out.toString()
        }

        /** Enabled Adblock Plus lists that only have the lossy legacy copy and wait for a fresh download. */
        fun listsPendingRefresh(): List<String> =
            BlocklistCatalog.sources
                .filter { isEnabled(it.id) && !it.hostsFormat && !rawFile(it.id).isFile && legacyDomainsFile(it.id).isFile }
                .map { it.id }

        fun lastUpdated(id: String): Long =
            (rawFile(id).takeIf { it.isFile } ?: legacyDomainsFile(id).takeIf { it.isFile })?.lastModified() ?: 0L
    }
