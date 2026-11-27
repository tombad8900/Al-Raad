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
        val ruleMatch = domainRule.matchEntire(line.lowercase())
        return when {
            line.isEmpty() || line.startsWith("!") || line.startsWith("[") -> null
            line.startsWith("@@") || line.contains("##") || line.contains("#@#") -> null
            ruleMatch != null -> ruleMatch.groupValues[1].trimEnd('.')
            isPlainHostsLine(line) -> HostsParser.parse(line)
            else -> null
        }
    }

    private fun isPlainHostsLine(line: String): Boolean = !line.startsWith("|") && !line.contains("*")
}
