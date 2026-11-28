package com.alraad.core.adblocker.impl

/**
 * Extracts the hosts-equivalent subset of Adblock-Plus-format lists: only rules whose
 * entire meaning is "block this domain" (`||domain^`, `||domain`, optional `$important`).
 * Everything else is counted as unsupported instead of being widened into a whole-domain
 * block: rules with a path (`||youtube.com/pagead/`), wildcards, `$third-party`, `domain=`,
 * resource-type or `badfilter` options, exception rules (`@@`), cosmetic filters (`##`,
 * `#@#`) and scriptlets. Widening those blocked whole sites such as google.com on real
 * lists. Exceptions are not evaluated; instead any domain block that an `@@||host` rule in the
 * same list names (same host, a subdomain, or a parent) is dropped, so an ignored exception
 * can never leave a broader block active than the list intended. Also accepts plain hosts
 * lines so one parser serves both formats. This is an honest subset, not a full ABP engine (that stays spike-gated).
 */
object AdblockPlusDomainParser {
    private val pureDomainRule =
        Regex("^\\|\\|([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,})\\^?(\\\$important)?$")

    /** Supported domains plus how many rules of each unsupported class were skipped. */
    data class Parsed(
        val domains: Set<String>,
        val unsupportedNetwork: Int,
        val cosmeticOrScriptlet: Int,
        val exceptions: Int,
        val droppedByException: Int,
    )

    private val exceptionHost = Regex("^@@\\|\\|([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,})")

    fun parse(text: String): Set<String> = parseDetailed(text).domains

    fun parseDetailed(text: String): Parsed {
        val domains = HashSet<String>()
        var unsupportedNetwork = 0
        var cosmetic = 0
        var exceptions = 0
        val exceptionHosts = HashSet<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            val lower = line.lowercase()
            when {
                line.isEmpty() || line.startsWith("!") || line.startsWith("[") -> Unit
                line.startsWith("@@") -> {
                    exceptions++
                    exceptionHost.find(lower)?.groupValues?.get(1)?.let(exceptionHosts::add)
                }
                line.contains("##") || line.contains("#@#") || line.contains("#?#") -> cosmetic++
                line.startsWith("|") || line.startsWith("/") -> {
                    val domain = pureDomainRule.matchEntire(lower)?.groupValues?.get(1)
                    if (domain != null) domains += domain else unsupportedNetwork++
                }
                isPlainHostsLine(line) -> HostsParser.parse(line).firstOrNull()?.let(domains::add)
                else -> unsupportedNetwork++
            }
        }
        val dropped = domains.filterTo(HashSet()) { d -> exceptionHosts.any { e -> overlaps(d, e) } }
        domains.removeAll(dropped)
        return Parsed(domains, unsupportedNetwork, cosmetic, exceptions, dropped.size)
    }

    private fun overlaps(
        blocked: String,
        exception: String,
    ): Boolean = blocked == exception || blocked.endsWith(".$exception") || exception.endsWith(".$blocked")

    private fun isPlainHostsLine(line: String): Boolean = !line.contains("*") && !line.contains("$")
}
