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
        /** Raised only by tests; a download below this many domains never promotes. */
        internal var minDomains = 100

    private val dir = File(context.filesDir, "blocklists")
    private val prefs = context.getSharedPreferences("adblock_lists", Context.MODE_PRIVATE)

    fun isEnabled(id: String): Boolean =
        prefs.getBoolean("enabled_$id", BlocklistCatalog.byId(id)?.enabledByDefault ?: true)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

    fun exceptions(): Set<String> = prefs.getStringSet("exceptions", emptySet()).orEmpty()

    fun setException(host: String, excepted: Boolean) {
        val updated = if (excepted) exceptions() + host.lowercase() else exceptions() - host.lowercase()
        prefs.edit().putStringSet("exceptions", updated).apply()
    }

    /** Parses candidate text and promotes it only when it yields enough domains. */
    fun promote(id: String, text: String): Boolean {
        val domains = AdblockPlusDomainParser.parse(text)
        if (domains.size < minDomains) return false
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
            if (isEnabled(source.id)) storedDomains(source.id)?.let(merged::addAll)
        }
        return merged
    }

    fun lastUpdated(id: String): Long = File(dir, "$id.domains").takeIf { it.isFile }?.lastModified() ?: 0L
}
