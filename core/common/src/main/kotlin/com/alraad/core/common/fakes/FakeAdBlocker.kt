package com.alraad.core.common.fakes

import com.alraad.core.common.contracts.AdBlocker

/**
 * Rule-driven in-memory [AdBlocker] for tests and development.
 * Blocks any URL whose host matches [blockedHosts]; serves fixed cosmetic CSS per host.
 */
class FakeAdBlocker(
    private val blockedHosts: Set<String> = emptySet(),
    private val cosmeticCssByHost: Map<String, String> = emptyMap(),
) : AdBlocker {
    val checkedUrls = mutableListOf<String>()

    override suspend fun shouldBlock(url: String): Boolean {
        checkedUrls += url
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return blockedHosts.any { blocked -> host == blocked || host.endsWith(".$blocked") }
    }

    override fun cosmeticCss(host: String): String = cosmeticCssByHost[host].orEmpty()
}
