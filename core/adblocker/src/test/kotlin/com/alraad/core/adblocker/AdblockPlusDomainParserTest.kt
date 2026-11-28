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
            ||track.example.net^${'$'}important
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

    @Test fun `rules narrowed by path or options are unsupported, not widened to the whole domain`() {
        val text =
            """
            ||youtube.com/pagead/
            ||google.com/adsense/search/ads.js
            ||linkedin.com^${'$'}third-party
            ||example.org^${'$'}script,domain=~example.net
            ||cnstrc.com^${'$'}badfilter
            ||googleapis.com^*/gen_204?
            ||ads.example.com^
            """.trimIndent()
        val parsed = AdblockPlusDomainParser.parseDetailed(text)
        assertEquals(setOf("ads.example.com"), parsed.domains)
        assertEquals(6, parsed.unsupportedNetwork)
    }

    @Test fun `counts cosmetic scriptlet and exception rules separately`() {
        val parsed =
            AdblockPlusDomainParser.parseDetailed(
                "a.example.com##.ad\nb.example.com##+js(trusted-click-element, #x)\n@@||c.example.com^\n||d.example.com^",
            )
        assertEquals(setOf("d.example.com"), parsed.domains)
        assertEquals(2, parsed.cosmeticOrScriptlet)
        assertEquals(1, parsed.exceptions)
    }

    @Test fun `a block that a same-list exception names is dropped instead of left broader than intended`() {
        val text =
            """
            ||example.com^
            @@||cdn.example.com^${'$'}script
            ||ads.other.net^
            @@||other.net/allowed/
            ||keep.example.org^
            """.trimIndent()
        val parsed = AdblockPlusDomainParser.parseDetailed(text)
        assertEquals(setOf("keep.example.org"), parsed.domains)
        assertEquals(2, parsed.droppedByException)
    }
}
