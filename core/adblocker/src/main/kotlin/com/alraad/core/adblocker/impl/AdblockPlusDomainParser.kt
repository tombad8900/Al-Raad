package com.alraad.core.adblocker.impl

/**
 * Extracts the hosts-equivalent subset of Adblock-Plus-format lists: `||domain^`
 * network rules only. Cosmetic filters (`##`, `#@#`), exception rules (`@@`),
 * and pattern rules without a literal domain are ignored — honest subset, not a
 * full ABP engine (that stays spike-gated). Also accepts plain hosts lines so one
 * parser serves both formats.
 */
object AdblockPlusDomainParser {
    private val domainRule = Regex("^\\|\\|([a-z0-9.-]+\\.[a-z]{2,})\\^?.*$")

    fun parse(text: String): Set<String> {
        val domains = HashSet<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            if (line.startsWith("@@") || line.contains("##") || line.contains("#@#")) continue
            val match = domainRule.matchEntire(line.lowercase())
            if (match != null) {
                domains += match.groupValues[1].trimEnd('.')
            } else if (!line.startsWith("||") && !line.startsWith("|") && !line.contains("*")) {
                domains += HostsParser.parse(line)
            }
        }
        return domains
    }
}
