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

        /** Parses candidate text and promotes it only when it yields enough domains. */
        fun promote(
            id: String,
            text: String,
        ): Boolean {
            val domains = AdblockPlusDomainParser.parse(text)
            if (domains.size < effectiveMin(id)) return false
            dir.mkdirs()
            val domainsTmp = File(dir, "$id.tmp")
            val rawTmp = File(dir, "$id.raw.tmp")
            // Stage both files first: a failure here leaves the last-known-good pair untouched. Then promote the
            // raw text (the engine's source) before the domains; a crash between the two renames leaves new raw plus
            // old domains, which is still a usable list, never a missing or half-written one.
            val promoted =
                runCatching {
                    domainsTmp.writeText(domains.sorted().joinToString("\n"))
                    rawTmp.writeText(text)
                    check(rawTmp.renameTo(File(dir, "$id.raw"))) { "atomic raw promote failed for $id" }
                    check(domainsTmp.renameTo(File(dir, "$id.domains"))) { "atomic promote failed for $id" }
                }.isSuccess
            if (!promoted) {
                domainsTmp.delete()
                rawTmp.delete()
            }
            return promoted
        }

        fun storedDomains(id: String): Set<String>? {
            val file = File(dir, "$id.domains")
            return if (file.isFile) file.readLines().filterTo(HashSet()) { it.isNotBlank() } else null
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
         * Rule text for the engine: raw text of every enabled list, or domain-anchored rules rebuilt from the
         * stored domains for a list promoted before raw text was kept.
         */
        fun activeEngineRules(): String {
            val out = StringBuilder()
            for (source in BlocklistCatalog.sources) {
                if (!isEnabled(source.id)) continue
                val raw = File(dir, "${source.id}.raw")
                if (raw.isFile) {
                    out.append(raw.readText()).append('\n')
                } else {
                    storedDomains(source.id)?.forEach { out.append("||").append(it).append("^\n") }
                }
            }
            return out.toString()
        }

        fun lastUpdated(id: String): Long = File(dir, "$id.domains").takeIf { it.isFile }?.lastModified() ?: 0L
    }
