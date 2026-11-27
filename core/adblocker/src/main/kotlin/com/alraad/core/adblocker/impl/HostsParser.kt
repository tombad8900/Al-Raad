package com.alraad.core.adblocker.impl

/** Parses hosts-file format blocklists ("0.0.0.0 ads.example.com", '#' comments). */
object HostsParser {
    private val whitespace = Regex("\\s+")

    fun parse(text: String): Set<String> {
        val hosts = HashSet<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(whitespace)
            val candidates = if (parts.size > 1 && isIp(parts[0])) parts.drop(1) else parts
            for (candidate in candidates) {
                val host = candidate.trim().lowercase().trimEnd('.')
                if (host.isNotEmpty() && !isIp(host) && host != "localhost") hosts += host
            }
        }
        return hosts
    }

    private fun isIp(value: String): Boolean = value.all { it.isDigit() || it == '.' || it == ':' } && value.any { it == '.' || it == ':' }
}
