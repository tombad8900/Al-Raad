package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.AdblockPlusDomainParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdblockPlusDomainParserTest {
    @Test fun `extracts literal domain rules and skips everything else`() {
        val text =
            """
            ! Title: EasyList
            [Adblock Plus 2.0]
            ||ads.example.com^
            ||track.example.net^$third-party
            @@||allowed.example.com^
            example.com##.ad-banner
            example.com#@#.ad-banner
            /banner/*/ad.js
            |http://prefix-only.example.org/
            """.trimIndent()
        val domains = AdblockPlusDomainParser.parse(text)
        assertTrue("ads.example.com" in domains)
        assertTrue("track.example.net" in domains)
        assertFalse("allowed.example.com" in domains)
        assertFalse(domains.any { it.contains('/') || it.contains('*') })
        assertEquals(2, domains.size)
    }

    @Test fun `accepts hosts-format lines through the same parser`() {
        val domains = AdblockPlusDomainParser.parse("0.0.0.0 ads.example.com\n127.0.0.1 track.example.net")
        assertEquals(setOf("ads.example.com", "track.example.net"), domains)
    }
}
