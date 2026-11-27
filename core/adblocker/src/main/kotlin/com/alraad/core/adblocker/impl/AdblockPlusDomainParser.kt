package com.alraad.core.adblocker.impl

/**
 * Extracts the hosts-equivalent subset of Adblock-Plus-format lists: `||domain^`
 * network rules only. Cosmetic filters (`##`, `#@#`), exception rules (`@@`),
 * and pattern rules without a literal domain are ignored. This is an honest
 * subset, not a full ABP engine (that stays spike-gated). Also accepts plain
 * hosts lines so one parser serves both formats.
 */
object AdblockPlusDomainParser {
    private val domainRule = Regex("^\\|\\|([a-z0-9.-]+\\.[a-z]{2,})\\^?.*$")

    fun parse(text: String): Set<String> {
        val domains = HashSet<String>()
        for (rawLine in text.lineSequence()) {
            extractDomain(rawLine.trim())?.let(domains::add)
        }
        return domains
    }

    private fun extractDomain(line: String): String? {
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return null
        if (line.startsWith("@@") || line.contains("##") || line.contains("#@#")) return null
        domainRule.matchEntire(line.lowercase())?.let { return it.groupValues[1].trimEnd('.') }
        if (isPlainHostsLine(line)) return HostsParser.parse(line)
        return null
    }

    private fun isPlainHostsLine(line: String): Boolean = !line.startsWith("|") && !line.contains("*")
}
